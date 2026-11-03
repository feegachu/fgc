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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 설명 : FUN-029 부활 사건 후속 처리기. 실효됐던 계약이 부활(LAPSED→REVIVED)하면 정책·효력일 기준으로 지급 재개 대상과 비대상을
 * 가르고 근거를 effect 로 남긴다. 판단은 {@link ContractStatusPaymentRule} 한 곳을 쓴다.
 *
 * <ul>
 *   <li>재개 대상: 부활 효력일 이후(정책이 허용한 미래) 회차만 PLANNED 로 되돌리고 그 귀속의 지급 건 보류를 해제한다.
 *       미확정 지급 건은 보류만 풀며 자동으로 확정·지급하지 않는다.</li>
 *   <li>비대상: 실효 기간(NO_RETROACTIVE)의 회차는 CANCELLED, 지급 건은 보류 그대로 두고 비대상 사유를 effect 로 남긴다.
 *       다른 사유의 보류가 남은 지급 건은 규칙이 풀지 않으므로 blocked 가 유지된다.</li>
 *   <li>확정·기지급·월잠금 결과는 바꾸지 않는다.</li>
 * </ul>
 *
 * <p>납입 재개(UNPAID→ACTIVE)는 FUN-028 처리기(제48조)의 몫이다. worker 가 계약 잠금을 잡은 한 트랜잭션에서 부르고
 * 예외를 던지면 모두 되돌려지며, 같은 사건을 다시 처리해도 같은 결과로 수렴한다.
 *
 * @version 1.0
 * @since 2026-11-03
 */
@Component
@RequiredArgsConstructor
public class PaymentResumeFollowUpProcessor implements ContractStatusFollowUpProcessor {

    public static final String JOB = "PaymentResumeJob";
    private static final String PLANNED = "PLANNED";
    private static final String RELEASED = "RELEASED";
    private static final String EXCLUDED = "EXCLUDED";
    private static final Set<String> RESUMABLE_LINE_STATES = Set.of("CANCELLED", "HOLD");

    private final ContractStatusPaymentRule rule;
    private final PaymentHoldRepository holds;
    private final JdbcTemplate jdbc;

    @Override
    public String jobName() {
        return JOB;
    }

    @Override
    public boolean supports(ContractStatusEvent event) {
        return event.getNewStatus() == ContractStatus.REVIVED;
    }

    @Override
    public List<ContractStatusEffect> process(ContractStatusEvent event) {
        Long contractId = event.getContractId();
        List<ContractStatusEffect> recorded = new ArrayList<>();
        for (ContractStatusEffect effect : rule.decide(event, holds.openHeldPaymentIds(contractId))) {
            if (effect.entityType() == EntityType.SCHEDULE_LINE && PLANNED.equals(effect.nextState())
                    && RESUMABLE_LINE_STATES.contains(effect.previousState())) {
                // 지급예정일은 그대로 둔다. 부활은 실효 기간을 소급하지 않고 효력일 이후 회차만 되살린다.
                jdbc.update("""
                        UPDATE fgc.schedule_line SET line_status = 'PLANNED'
                         WHERE schedule_line_id = ? AND line_status IN ('CANCELLED', 'HOLD')
                        """, effect.entityId());
                recorded.add(effect);
            } else if (effect.entityType() == EntityType.PAYMENT && RELEASED.equals(effect.nextState())) {
                holds.releaseOpen(effect.entityId(), event.getContractId(), event.getContractStatusEventId());
                recorded.add(effect);
            } else if (EXCLUDED.equals(effect.nextState())) {
                recorded.add(effect);
            }
        }
        // 규칙은 바뀌는 대상만 돌려주므로 되살리지 않은 대상과 그 근거는 같은 규칙의 판단에서 따로 만들어 남긴다.
        Set<String> already = new HashSet<>();
        recorded.forEach(effect -> already.add(effect.entityType() + ":" + effect.entityId()));
        for (ContractStatusEffect effect : rule.notPayable(event)) {
            if (already.add(effect.entityType() + ":" + effect.entityId())) {
                recorded.add(effect);
            }
        }
        return recorded;
    }
}
