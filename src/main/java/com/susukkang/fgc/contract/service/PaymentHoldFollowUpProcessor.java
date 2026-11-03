package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import com.susukkang.fgc.contract.dto.ContractStatusEffect.EntityType;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import com.susukkang.fgc.contract.repository.PaymentHoldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * 설명 : FUN-028 상태 사건 후속 처리기. 미납·실효·해지·만기·청약철회 사건이면 미확정 지급 건을 보류하고
 * 미확정 예정 회차를 보류(HOLD)·중단(CANCELLED)한다. 판단은 {@link ContractStatusPaymentRule} 한 곳을 쓰므로
 * 즉시·일일·월 경로가 같은 사건에 같은 대상·사유를 낸다.
 *
 * <p>확정·기지급·월잠금 대상은 바꾸지 않고 제외 사유만 효과로 남긴다. 재개(PLANNED·RELEASED)는 FUN-029 처리기의 몫이라
 * 여기서 적용하거나 기록하지 않는다. worker 가 계약 잠금을 잡은 한 트랜잭션에서 부르고, 예외를 던지면 모두 되돌려진다.
 *
 * @version 1.0
 * @since 2026-11-03
 */
@Component
@RequiredArgsConstructor
public class PaymentHoldFollowUpProcessor implements ContractStatusFollowUpProcessor {

    public static final String JOB = "PAYMENT_HOLD";
    private static final Set<ContractStatus> HOLD_TRIGGERS = EnumSet.of(ContractStatus.UNPAID, ContractStatus.LAPSED,
            ContractStatus.TERMINATED, ContractStatus.MATURED, ContractStatus.CANCELLED);
    private static final Set<String> APPLIED_LINE_STATES = Set.of("HOLD", "CANCELLED");
    private static final String HELD = "HELD";
    private static final String EXCLUDED = "EXCLUDED";

    private final ContractStatusPaymentRule rule;
    private final PaymentHoldRepository holds;
    private final JdbcTemplate jdbc;

    @Override
    public String jobName() {
        return JOB;
    }

    @Override
    public boolean supports(ContractStatusEvent event) {
        return HOLD_TRIGGERS.contains(event.getNewStatus());
    }

    @Override
    public List<ContractStatusEffect> process(ContractStatusEvent event) {
        List<ContractStatusEffect> recorded = new ArrayList<>();
        for (ContractStatusEffect effect : rule.decide(event, holds.openHeldPaymentIds(event.getContractId()))) {
            if (effect.entityType() == EntityType.SCHEDULE_LINE) {
                if (APPLIED_LINE_STATES.contains(effect.nextState())) {
                    applyLine(effect);
                    recorded.add(effect);
                } else if (EXCLUDED.equals(effect.nextState())) {
                    recorded.add(effect);
                }
            } else if (HELD.equals(effect.nextState())) {
                holds.insert(effect.entityId(), event.getContractStatusEventId(), effect.policyVersionId(), effect.reasonCode());
                recorded.add(effect);
            } else if (EXCLUDED.equals(effect.nextState())) {
                recorded.add(effect);
            }
        }
        return recorded;
    }

    /** 규칙이 이미 확정·월잠금 회차를 걸러 냈지만 DB 상태가 그 사이 바뀌었을 수 있어 조건으로 한 번 더 지킨다. */
    private void applyLine(ContractStatusEffect effect) {
        jdbc.update("""
                UPDATE fgc.schedule_line SET line_status = ?
                 WHERE schedule_line_id = ? AND line_status NOT IN ('CONFIRMED', 'MATCHED', 'ADJUSTED')
                """, effect.nextState(), effect.entityId());
    }
}
