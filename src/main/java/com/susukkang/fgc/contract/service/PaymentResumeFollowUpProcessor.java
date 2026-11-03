package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import com.susukkang.fgc.contract.dto.ContractStatusEffect.EntityType;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.contract.repository.PaymentHoldRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
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
    static final String NO_RETROACTIVE = "NO_RETROACTIVE";
    private static final String PLANNED = "PLANNED";
    private static final String RELEASED = "RELEASED";
    private static final String EXCLUDED = "EXCLUDED";
    private static final Set<String> RESUMABLE_LINE_STATES = Set.of("CANCELLED", "HOLD");

    private final ContractStatusPaymentRule rule;
    private final PaymentHoldRepository holds;
    private final JdbcTemplate jdbc;
    private final ContractStatusEventRepository events;

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
                holds.releaseOpen(effect.entityId(), event.getContractStatusEventId());
                recorded.add(effect);
            } else if (EXCLUDED.equals(effect.nextState())) {
                recorded.add(effect);
            }
        }
        recorded.addAll(notResumed(event));
        return recorded;
    }

    /**
     * 비대상과 그 근거: 직전 실효일부터 부활 전날까지의 중단 회차와, 처리 뒤에도 보류가 남은 지급 건.
     * 규칙은 바뀌는 대상만 돌려주므로 "왜 되살리지 않았는지"는 여기서 따로 남겨야 영향 조회에서 보인다.
     */
    private List<ContractStatusEffect> notResumed(ContractStatusEvent revival) {
        List<ContractStatusEvent> timeline = events.findByContractIdOrderByEffectiveAtAscEventSeqAsc(revival.getContractId());
        LocalDate revivedOn = day(revival);
        LocalDate lapsedOn = null;
        for (int i = timeline.indexOf(revival) - 1; i >= 0; i--) {
            if (timeline.get(i).getNewStatus() == ContractStatus.LAPSED) {
                lapsedOn = day(timeline.get(i));
                break;
            }
        }
        if (lapsedOn == null) {
            return List.of();
        }
        Long policyVersionId = rule.policyVersionId(revival.getContractId());
        String why = "부활은 실효 기간(" + lapsedOn + "~" + revivedOn.minusDays(1) + ")을 소급하지 않습니다(NO_RETROACTIVE). ";
        List<ContractStatusEffect> notResumed = new ArrayList<>();
        notResumed.addAll(jdbc.query("""
                SELECT sl.schedule_line_id, sl.line_status, sl.expected_amount
                  FROM fgc.schedule_line sl
                  JOIN fgc.schedule_header sh ON sh.schedule_header_id = sl.schedule_header_id
                 WHERE sh.contract_id = ? AND sh.schedule_purpose = 'OPERATIONAL' AND sh.active_yn
                   AND sh.status NOT IN ('CONFIRMED', 'MATCHED', 'ADJUSTED', 'CANCELLED')
                   AND sl.line_status = 'CANCELLED' AND sl.due_date >= ? AND sl.due_date < ?
                 ORDER BY sl.schedule_line_id
                """, (rs, row) -> new ContractStatusEffect(EntityType.SCHEDULE_LINE, rs.getLong(1), rs.getString(2), EXCLUDED,
                rs.getBigDecimal(3), policyVersionId, NO_RETROACTIVE, why + "이 회차는 중단 상태로 둡니다."),
                revival.getContractId(), lapsedOn, revivedOn));
        for (ContractStatusEffect payment : stillHeld(revival.getContractId(), policyVersionId, why)) {
            notResumed.add(payment);
        }
        return notResumed;
    }

    /** 부활 처리 뒤에도 미해제 보류가 남은 지급 건. 이 지급 건은 계속 확정할 수 없다. */
    private List<ContractStatusEffect> stillHeld(Long contractId, Long policyVersionId, String why) {
        return jdbc.query("""
                SELECT DISTINCT ct.commission_transaction_id, ct.status, ct.amount
                  FROM fgc.payment_hold h
                  JOIN fgc.commission_transaction ct ON ct.commission_transaction_id = h.commission_transaction_id
                  JOIN fgc.transaction_attribution ta ON ta.commission_transaction_id = ct.commission_transaction_id
                 WHERE ta.contract_id = ? AND h.released_at IS NULL AND ct.status = 'DRAFT'
                 ORDER BY ct.commission_transaction_id
                """, (rs, row) -> new ContractStatusEffect(EntityType.PAYMENT, rs.getLong(1), rs.getString(2), EXCLUDED,
                (BigDecimal) rs.getBigDecimal(3), policyVersionId, NO_RETROACTIVE,
                why + "보류가 남은 지급 건이라 계속 확정할 수 없습니다."), contractId);
    }

    private static LocalDate day(ContractStatusEvent event) {
        return event.getEffectiveAt().atZoneSameInstant(DateUtil.SEOUL_ZONE).toLocalDate();
    }
}
