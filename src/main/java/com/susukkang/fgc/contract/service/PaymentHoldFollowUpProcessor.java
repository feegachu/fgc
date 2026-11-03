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

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 설명 : FUN-028 상태 사건 후속 처리기. 미납·실효·해지·만기·청약철회 사건이면 미확정 지급 건을 보류하고
 * 미확정 예정 회차를 보류(HOLD)·중단(CANCELLED)한다. 판단은 {@link ContractStatusPaymentRule} 한 곳을 쓰므로
 * 즉시·일일·월 경로가 같은 사건에 같은 대상·사유를 낸다.
 *
 * <p>확정·기지급·월잠금 대상은 바꾸지 않고 제외 사유만 효과로 남긴다. 미납이 정상으로 돌아온 납입 재개(UNPAID→ACTIVE)는
 * 운영정책서 제48조대로 이 처리기가 맡는다: 보류 회차를 PLANNED 로 되돌리면서 지급예정일을 재개일 기준으로 다시 잡고 지급 보류를 해제한다.
 * 부활(LAPSED→REVIVED)의 재개는 FUN-029 처리기의 몫이라 적용하거나 기록하지 않는다. worker 가 계약 잠금을 잡은 한 트랜잭션에서 부르고, 예외를 던지면 모두 되돌려진다.
 *
 * @version 1.0
 * @since 2026-11-03
 */
@Component
@RequiredArgsConstructor
public class PaymentHoldFollowUpProcessor implements ContractStatusFollowUpProcessor {

    public static final String JOB = "PaymentHoldJob";
    static final Set<ContractStatus> HOLD_TRIGGERS = EnumSet.of(ContractStatus.UNPAID, ContractStatus.LAPSED,
            ContractStatus.TERMINATED, ContractStatus.MATURED, ContractStatus.CANCELLED);
    private static final Set<String> APPLIED_LINE_STATES = Set.of("HOLD", "CANCELLED");
    private static final String HELD = "HELD";
    private static final String EXCLUDED = "EXCLUDED";
    private static final String PLANNED = "PLANNED";
    private static final String RELEASED = "RELEASED";
    private static final String UNPAID_RESUMED = "UNPAID_RESUMED";

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
        return HOLD_TRIGGERS.contains(event.getNewStatus()) || isPaymentResumed(event);
    }

    /** 미납이 정상으로 돌아온 사건. 이 경우의 지급 재개는 FUN-028 몫이다(제48조). */
    private static boolean isPaymentResumed(ContractStatusEvent event) {
        return event.getNewStatus() == ContractStatus.ACTIVE && event.getPreviousStatus() == ContractStatus.UNPAID;
    }

    @Override
    public List<ContractStatusEffect> process(ContractStatusEvent event) {
        boolean resumed = isPaymentResumed(event);
        List<ContractStatusEffect> recorded = new ArrayList<>();
        for (ContractStatusEffect effect : rule.decide(event, holds.openHeldPaymentIds(event.getContractId()))) {
            if (resumed) {
                resume(event, effect).ifPresent(recorded::add);
            } else if (effect.entityType() == EntityType.SCHEDULE_LINE) {
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

    /**
     * 납입 재개: 미납으로 보류됐던 회차(HOLD)와 지급 건을 푼다. 재개일 당일과 그 이후 회차도 정상 상태라 PLANNED 로 돌린다.
     * 지급예정일은 재개 전에 있던 회차(UNPAID_RESUMED)만 재개일로 다시 잡는다. 다른 사유로 중단된(CANCELLED) 회차는 소급하지 않는다.
     */
    private Optional<ContractStatusEffect> resume(ContractStatusEvent event, ContractStatusEffect effect) {
        if (effect.entityType() == EntityType.SCHEDULE_LINE && PLANNED.equals(effect.nextState())
                && "HOLD".equals(effect.previousState())) {
            boolean dueBeforeResume = UNPAID_RESUMED.equals(effect.reasonCode());
            LocalDate resumedOn = resumeDate(event);
            // 원래 날짜로 되돌리면 원래 달에는 실제 지급이 없고 재개한 달에는 예상이 없어 대사가 갈라진다(2026-10-13 PL 결정).
            jdbc.update("""
                    UPDATE fgc.schedule_line SET line_status = 'PLANNED', due_date = CASE WHEN ? THEN ?::date ELSE due_date END
                     WHERE schedule_line_id = ? AND line_status = 'HOLD'
                    """, dueBeforeResume, resumedOn, effect.entityId());
            return Optional.of(dueBeforeResume ? withDueDateNote(effect, resumedOn) : effect);
        }
        if (effect.entityType() == EntityType.PAYMENT && RELEASED.equals(effect.nextState())) {
            holds.releaseOpen(effect.entityId(), event.getContractStatusEventId());
            return Optional.of(effect);
        }
        return Optional.empty();
    }

    private static LocalDate resumeDate(ContractStatusEvent event) {
        return event.getEffectiveAt().atZoneSameInstant(DateUtil.SEOUL_ZONE).toLocalDate();
    }

    private static ContractStatusEffect withDueDateNote(ContractStatusEffect effect, LocalDate resumedOn) {
        return new ContractStatusEffect(effect.entityType(), effect.entityId(), effect.previousState(), effect.nextState(),
                effect.amount(), effect.policyVersionId(), effect.reasonCode(),
                effect.reason() + " 지급예정일을 재개일 " + resumedOn + "로 다시 잡았습니다.");
    }

    /**
     * 새 스케줄 버전은 원래 지급예정일로 회차를 다시 만든다. 최신 사건이 납입 재개이면 재개 전(미납일~재개 전날)에 있던
     * PLANNED 회차의 지급예정일을 재개일로 다시 잡는다. 이미 재개일이면 범위 밖이라 되풀이해도 바뀌지 않는다.
     */
    private void resetResumedDueDates(List<ContractStatusEvent> timeline, ContractStatusEvent latest) {
        if (!isPaymentResumed(latest)) {
            return;
        }
        LocalDate resumedOn = resumeDate(latest);
        LocalDate unpaidOn = null;
        for (int i = timeline.indexOf(latest) - 1; i >= 0; i--) {
            if (timeline.get(i).getNewStatus() == ContractStatus.UNPAID) {
                unpaidOn = resumeDate(timeline.get(i));
                break;
            }
        }
        if (unpaidOn == null) {
            return;
        }
        List<Long> lines = jdbc.query("""
                SELECT sl.schedule_line_id
                  FROM fgc.schedule_line sl
                  JOIN fgc.schedule_header sh ON sh.schedule_header_id = sl.schedule_header_id
                 WHERE sh.contract_id = ? AND sh.schedule_purpose = 'OPERATIONAL' AND sh.active_yn
                   AND sh.status NOT IN ('CONFIRMED', 'MATCHED', 'ADJUSTED', 'CANCELLED')
                   AND sl.line_status = 'PLANNED' AND sl.due_date >= ? AND sl.due_date < ?
                   AND NOT EXISTS (SELECT 1 FROM fgc.validation_run vr
                                    WHERE vr.status = 'FINALIZED' AND vr.validation_month = sl.due_month)
                 ORDER BY sl.schedule_line_id
                """, (rs, row) -> rs.getLong(1), latest.getContractId(), unpaidOn, resumedOn);
        for (Long lineId : lines) {
            jdbc.update("UPDATE fgc.schedule_line SET due_date = ? WHERE schedule_line_id = ?", resumedOn, lineId);
        }
    }

    /**
     * 스케줄 새 버전(계약 수정·수동 재생성·정책 변경)은 HOLD·CANCELLED 회차를 PLANNED 로 다시 만든다. 새 버전을 만든 같은 트랜잭션에서
     * 이 계약의 최신 상태 사건으로 다시 판단해 보류·중단을 맞춘다(인터페이스정의서 §4-4-2). 규칙은 지금 상태와 비교하므로 다시 맞출 회차만 바뀐다.
     * 새 작업이나 effect 는 만들지 않는다(PM 결정 2026-11-01).
     * 보류·중단 사건이 없는 계약은 정책을 해석하지 않고 그대로 둔다.
     */
    public void reapplyLatest(Long contractId) {
        List<ContractStatusEvent> timeline = events.findByContractIdOrderByEffectiveAtAscEventSeqAsc(contractId);
        if (timeline.stream().noneMatch(this::supports)) {
            return;
        }
        ContractStatusEvent latest = timeline.get(timeline.size() - 1);
        // 같은 사건이면 언제 판단해도 결과가 같으므로 새 작업이나 effect 행은 만들지 않는다. 근거는 원래 작업의 effect 로 남아 있다.
        for (ContractStatusEffect effect : rule.decide(latest, holds.openHeldPaymentIds(contractId))) {
            if (effect.entityType() == EntityType.SCHEDULE_LINE && APPLIED_LINE_STATES.contains(effect.nextState())) {
                applyLine(effect);
            }
        }
        resetResumedDueDates(timeline, latest);
    }

    /** 규칙이 이미 확정·월잠금 회차를 걸러 냈지만 DB 상태가 그 사이 바뀌었을 수 있어 조건으로 한 번 더 지킨다. */
    private void applyLine(ContractStatusEffect effect) {
        jdbc.update("""
                UPDATE fgc.schedule_line SET line_status = ?
                 WHERE schedule_line_id = ? AND line_status NOT IN ('CONFIRMED', 'MATCHED', 'ADJUSTED')
                """, effect.nextState(), effect.entityId());
    }
}
