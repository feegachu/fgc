package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.common.code.ScheduleLineStatus;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import com.susukkang.fgc.contract.dto.ContractStatusEffect.EntityType;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import com.susukkang.fgc.contract.repository.ContractStatusEventRepository;
import com.susukkang.fgc.policy.service.ScheduleEligibilityPolicyService;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 설명 : FUN-015 계약상태별 지급조건 읽기 규칙. FUN-028·029 후속 처리기와 일·월 경로가 같은 판단을 쓰도록 한 곳에 둔다.
 * 사건까지의 상태 이력(§9-3 효력순)으로 회차·미확정 지급 건의 지급·보류·중단·재개를 판단하고 아무것도 쓰지 않는다.
 * 판단 기준은 SCHEDULE_ELIGIBILITY 정책 파라미터(인터페이스정의서 §9-5, 운영정책서 제48조)다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-01
 */
@Service
@RequiredArgsConstructor
public class ContractStatusPaymentRule {

    private static final Comparator<StatusPoint> EFFECTIVE_ORDER = Comparator
            .comparing((StatusPoint point) -> point.effectiveAt().toInstant())
            .thenComparingInt(StatusPoint::eventSeq);
    private static final Set<ScheduleLineStatus> CONFIRMED_LINES =
            Set.of(ScheduleLineStatus.CONFIRMED, ScheduleLineStatus.MATCHED, ScheduleLineStatus.ADJUSTED);
    // DB가 이 헤더의 회차 변경을 막는다(trg_schedule_line_update_guard).
    private static final Set<ScheduleHeaderStatus> CONFIRMED_HEADERS =
            Set.of(ScheduleHeaderStatus.CONFIRMED, ScheduleHeaderStatus.MATCHED, ScheduleHeaderStatus.ADJUSTED);
    private static final String EXCLUDED = "EXCLUDED";

    private final ContractStatusEventRepository events;
    private final ScheduleEligibilityPolicyService policies;
    private final JdbcTemplate jdbc;

    /**
     * 사건 시점의 판단. 처리기·일일·월 경로가 이 메서드로 판단해야 같은 사건·스냅샷에 같은 대상·사유가 나온다.
     * 정책은 계약일로 고르며, 없거나 파라미터가 다르면 FGC-IMPL-POLICY_MISSING을 던진다(통과로 보지 않는다).
     *
     * @param heldPaymentIds 상태 사건으로 이미 보류를 건 지급 건 ID(보류 저장은 FUN-028 #523). 없으면 빈 집합
     */
    @Transactional(readOnly = true)
    public List<ContractStatusEffect> decide(ContractStatusEvent event, Set<Long> heldPaymentIds) {
        Long contractId = event.getContractId();
        LocalDate contractDate = jdbc.queryForObject(
                "SELECT contract_date FROM fgc.insurance_contract WHERE contract_id = ?", LocalDate.class, contractId);
        Long policyVersionId = policies.resolve(contractDate);
        List<StatusPoint> timeline = events.findByContractIdOrderByEffectiveAtAscEventSeqAsc(contractId).stream()
                .map(ContractStatusPaymentRule::point)
                .toList();
        return judge(timeline, point(event), policyVersionId, lines(contractId), payments(contractId), heldPaymentIds);
    }

    private static StatusPoint point(ContractStatusEvent event) {
        return new StatusPoint(event.getEffectiveAt(), event.getEventSeq(), event.getNewStatus());
    }

    /** 활성 운영 스케줄의 회차. 4년·7년 시뮬레이션은 지급 대상이 아니고 취소된 헤더의 회차는 이미 무효라 넣지 않는다. */
    private List<Line> lines(Long contractId) {
        return jdbc.query("""
                SELECT sl.schedule_line_id, sl.due_date, sl.line_status, sh.status AS header_status, sl.expected_amount,
                       EXISTS (SELECT 1 FROM fgc.validation_run vr
                                WHERE vr.status = 'FINALIZED' AND vr.validation_month = sl.due_month) AS month_locked
                  FROM fgc.schedule_header sh
                  JOIN fgc.schedule_line sl ON sl.schedule_header_id = sh.schedule_header_id
                 WHERE sh.contract_id = ? AND sh.schedule_purpose = 'OPERATIONAL' AND sh.active_yn
                   AND sh.status <> 'CANCELLED'
                 ORDER BY sl.schedule_line_id
                """, (rs, row) -> new Line(rs.getLong("schedule_line_id"), rs.getObject("due_date", LocalDate.class),
                ScheduleLineStatus.valueOf(rs.getString("line_status")),
                ScheduleHeaderStatus.valueOf(rs.getString("header_status")),
                rs.getBigDecimal("expected_amount"), rs.getBoolean("month_locked")), contractId);
    }

    /** 이 계약에 귀속된 지급(PAYMENT) 건과 귀속일. 차감 건과 설계사 단위 귀속은 판단하지 않는다. */
    private List<Payment> payments(Long contractId) {
        Map<Long, Payment> payments = new LinkedHashMap<>();
        jdbc.query("""
                SELECT ct.commission_transaction_id, ct.status, ct.amount, ta.attribution_date
                  FROM fgc.commission_transaction ct
                  JOIN fgc.transaction_attribution ta ON ta.commission_transaction_id = ct.commission_transaction_id
                 WHERE ta.contract_id = ? AND ta.attribution_scope = 'CONTRACT' AND ct.cashflow_type = 'PAYMENT'
                 ORDER BY ct.commission_transaction_id, ta.attribution_date
                """, rs -> {
            Long id = rs.getLong("commission_transaction_id");
            Payment payment = payments.get(id);
            if (payment == null) {
                payment = new Payment(id, CommissionPaymentStatus.valueOf(rs.getString("status")),
                        rs.getBigDecimal("amount"), new ArrayList<>());
                payments.put(id, payment);
            }
            payment.attributionDates().add(rs.getObject("attribution_date", LocalDate.class));
        }, contractId);
        return List.copyOf(payments.values());
    }

    record StatusPoint(OffsetDateTime effectiveAt, int eventSeq, ContractStatus status) {
    }

    record Line(Long lineId, LocalDate dueDate, ScheduleLineStatus status, ScheduleHeaderStatus headerStatus,
                BigDecimal amount, boolean monthLocked) {
    }

    record Payment(Long transactionId, CommissionPaymentStatus status, BigDecimal amount,
                   List<LocalDate> attributionDates) {
    }

    // 심각한 순서. 귀속일이 여러 개인 지급 건은 가장 심각한 판단을 쓴다.
    private enum Outcome { PAY, HOLD, STOP }

    private enum Reason {
        STATUS_ACTIVE, STATUS_UNPAID, UNPAID_RESUMED, UNPAID_ENDED, STATUS_INACTIVE, CONTRACT_WITHDRAWN,
        ALREADY_CONFIRMED, MONTH_LOCKED
    }

    /** status: 판단일의 상태. 미납이 해소된 판단(UNPAID_*)이면 해소한 상태다. */
    private record Verdict(Outcome outcome, Reason reason, LocalDate date, ContractStatus status) {

        String explain(String dateLabel) {
            String at = dateLabel + " " + date + " 시점";
            return switch (reason) {
                case CONTRACT_WITHDRAWN -> "청약철회 계약은 처음부터 무효라 지급하지 않습니다.";
                case UNPAID_RESUMED -> at + " 미납이었으나 이후 상태(" + status.label() + ")로 납입이 재개되어 지급 대상입니다.";
                case UNPAID_ENDED -> at + " 미납이었고 이후 상태(" + status.label() + ")에 따라 지급하지 않습니다.";
                default -> at + " 계약 상태(" + status.label() + ")에 따라 " + switch (outcome) {
                    case PAY -> "지급 대상입니다.";
                    case HOLD -> "보류합니다.";
                    case STOP -> "지급하지 않습니다.";
                };
            };
        }
    }

    /**
     * 사건 {@code asOf}까지의 이력으로 판단한다. 나중에 기록된 사건은 보지 않아 같은 사건·스냅샷은 언제 판단해도 결과가 같다.
     *
     * @param heldPaymentIds 상태 사건으로 이미 보류를 건 지급 건(보류 저장은 FUN-028 #523)
     * @return 바뀌어야 할 대상과, 이 사건으로 판단이 달라졌지만 확정·월잠금이라 바꾸지 않은 대상(EXCLUDED). 바뀔 것이 없으면 넣지 않는다
     */
    static List<ContractStatusEffect> judge(List<StatusPoint> events, StatusPoint asOf, Long policyVersionId,
                                            List<Line> lines, List<Payment> payments, Set<Long> heldPaymentIds) {
        List<StatusPoint> timeline = events.stream()
                .filter(point -> EFFECTIVE_ORDER.compare(point, asOf) <= 0)
                .sorted(EFFECTIVE_ORDER)
                .toList();
        List<StatusPoint> earlier = timeline.subList(0, timeline.size() - 1);
        List<ContractStatusEffect> effects = new ArrayList<>();
        for (Line line : lines) {
            List<LocalDate> dates = List.of(line.dueDate());
            worst(dates, timeline)
                    .flatMap(verdict -> lineEffect(line, verdict, outcomeBefore(dates, earlier), policyVersionId))
                    .ifPresent(effects::add);
        }
        for (Payment payment : payments) {
            worst(payment.attributionDates(), timeline)
                    .flatMap(verdict -> paymentEffect(payment, verdict,
                            outcomeBefore(payment.attributionDates(), earlier), heldPaymentIds, policyVersionId))
                    .ifPresent(effects::add);
        }
        return effects;
    }

    /** 날짜가 여럿이면(지급 건의 귀속일) 가장 심각한 판단을 쓴다. */
    private static Optional<Verdict> worst(List<LocalDate> dates, List<StatusPoint> timeline) {
        if (timeline.isEmpty()) {
            return Optional.empty();
        }
        return dates.stream()
                .map(date -> verdictOn(date, timeline))
                .flatMap(Optional::stream)
                .max(Comparator.comparing(Verdict::outcome));
    }

    /** 이 사건 직전의 판단. 사건이 없던 날은 지급 대상이다(운영정책서 제13조). */
    private static Outcome outcomeBefore(List<LocalDate> dates, List<StatusPoint> earlier) {
        return worst(dates, earlier).map(Verdict::outcome).orElse(Outcome.PAY);
    }

    /**
     * 그 날짜가 끝날 때의 계약 상태로 판단한다(STATUS_MONTH_RULE=PAY_IF_ACTIVE_ON_DUE_DATE). 대사의
     * ContractStatusOnDueDateSql과 같은 경계다. 그날까지 사건이 없으면 판단하지 않는다(운영정책서 제13조, 지급 대상으로 남김).
     */
    private static Optional<Verdict> verdictOn(LocalDate date, List<StatusPoint> timeline) {
        if (timeline.get(timeline.size() - 1).status() == ContractStatus.CANCELLED) {
            // 청약철회는 처음부터 무효라 날짜와 무관하다(대사 NON_PAYABLE_ON_DUE_DATE와 같다).
            return Optional.of(new Verdict(Outcome.STOP, Reason.CONTRACT_WITHDRAWN, date, ContractStatus.CANCELLED));
        }
        int index = -1;
        while (index + 1 < timeline.size() && !seoulDate(timeline.get(index + 1)).isAfter(date)) {
            index++;
        }
        if (index < 0) {
            return Optional.empty();
        }
        ContractStatus status = timeline.get(index).status();
        return switch (status) {
            case ACTIVE, REVIVED -> Optional.of(new Verdict(Outcome.PAY, Reason.STATUS_ACTIVE, date, status));
            case LAPSED, TERMINATED, MATURED, CANCELLED ->
                    Optional.of(new Verdict(Outcome.STOP, Reason.STATUS_INACTIVE, date, status));
            case UNPAID -> Optional.of(unpaid(date, timeline, index));
            case APPLIED -> Optional.empty(); // §9-5 처리표에 없는 상태는 판단하지 않는다.
        };
    }

    /** 미납은 보류했다가 납입 재개면 지급, 실효·해지·만기·청약철회면 중단한다(2026-10-12·13 PL 결정, §9-5). */
    private static Verdict unpaid(LocalDate date, List<StatusPoint> timeline, int unpaidIndex) {
        for (int i = unpaidIndex + 1; i < timeline.size(); i++) {
            ContractStatus next = timeline.get(i).status();
            if (next == ContractStatus.UNPAID) {
                continue;
            }
            return next == ContractStatus.ACTIVE || next == ContractStatus.REVIVED
                    ? new Verdict(Outcome.PAY, Reason.UNPAID_RESUMED, date, next)
                    : new Verdict(Outcome.STOP, Reason.UNPAID_ENDED, date, next);
        }
        return new Verdict(Outcome.HOLD, Reason.STATUS_UNPAID, date, ContractStatus.UNPAID);
    }

    /**
     * 바꿀 수 있는 회차는 지금 상태와 비교해 같은 결과로 수렴하게 한다. 확정·월잠금 회차는 상태가 수렴하지 않으므로
     * 이 사건으로 판단이 달라질 때만 제외 사유를 남긴다(사건마다 같은 제외를 되풀이하지 않는다).
     */
    private static Optional<ContractStatusEffect> lineEffect(Line line, Verdict verdict, Outcome before,
                                                             Long policyVersionId) {
        ScheduleLineStatus target = switch (verdict.outcome()) {
            case PAY -> ScheduleLineStatus.PLANNED;
            case HOLD -> ScheduleLineStatus.HOLD;
            case STOP -> ScheduleLineStatus.CANCELLED;
        };
        boolean changedByEvent = before != verdict.outcome();
        String reason = verdict.explain("지급예정일");
        if (CONFIRMED_LINES.contains(line.status()) || CONFIRMED_HEADERS.contains(line.headerStatus())) {
            return target == ScheduleLineStatus.PLANNED || !changedByEvent ? Optional.empty()
                    : Optional.of(effect(EntityType.SCHEDULE_LINE, line.lineId(), line.status().name(), EXCLUDED,
                    line.amount(), policyVersionId, Reason.ALREADY_CONFIRMED,
                    "이미 확정된 회차라 바꾸지 않습니다(이력 보존). " + reason));
        }
        ScheduleLineStatus current = line.status() == ScheduleLineStatus.RESTARTED ? ScheduleLineStatus.PLANNED : line.status();
        if (current == target) {
            return Optional.empty();
        }
        if (line.monthLocked()) {
            return !changedByEvent ? Optional.empty()
                    : Optional.of(effect(EntityType.SCHEDULE_LINE, line.lineId(), line.status().name(), EXCLUDED,
                    line.amount(), policyVersionId, Reason.MONTH_LOCKED,
                    "확정된 검증월의 회차라 바꾸지 않습니다(이력 보존). " + reason));
        }
        return Optional.of(effect(EntityType.SCHEDULE_LINE, line.lineId(), line.status().name(), target.name(),
                line.amount(), policyVersionId, verdict.reason(), reason));
    }

    private static Optional<ContractStatusEffect> paymentEffect(Payment payment, Verdict verdict, Outcome before,
                                                                Set<Long> heldPaymentIds, Long policyVersionId) {
        boolean holdRequired = verdict.outcome() != Outcome.PAY;
        String reason = verdict.explain("귀속일");
        String previous = payment.status().name();
        return switch (payment.status()) {
            case CONFIRMED -> holdRequired && before != verdict.outcome()
                    ? Optional.of(effect(EntityType.PAYMENT, payment.transactionId(), previous, EXCLUDED,
                    payment.amount(), policyVersionId, Reason.ALREADY_CONFIRMED,
                    "이미 확정된 지급 건이라 보류하지 않습니다(이력 보존). " + reason))
                    : Optional.empty();
            case CANCELLED -> Optional.empty();
            case DRAFT -> {
                boolean held = heldPaymentIds.contains(payment.transactionId());
                if (holdRequired == held) {
                    yield Optional.empty();
                }
                yield Optional.of(effect(EntityType.PAYMENT, payment.transactionId(), previous,
                        holdRequired ? "HELD" : "RELEASED", payment.amount(), policyVersionId, verdict.reason(), reason));
            }
        };
    }

    private static ContractStatusEffect effect(EntityType type, Long id, String previous, String next, BigDecimal amount,
                                               Long policyVersionId, Reason reason, String text) {
        return new ContractStatusEffect(type, id, previous, next, amount, policyVersionId, reason.name(), text);
    }

    private static LocalDate seoulDate(StatusPoint point) {
        return point.effectiveAt().atZoneSameInstant(DateUtil.SEOUL_ZONE).toLocalDate();
    }
}
