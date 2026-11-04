package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.ScheduleHeaderStatus;
import com.susukkang.fgc.common.code.ScheduleLineStatus;
import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import com.susukkang.fgc.contract.service.ContractStatusPaymentRule.Line;
import com.susukkang.fgc.contract.service.ContractStatusPaymentRule.Payment;
import com.susukkang.fgc.contract.service.ContractStatusPaymentRule.StatusPoint;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.susukkang.fgc.common.code.ContractStatus.ACTIVE;
import static com.susukkang.fgc.common.code.ContractStatus.CANCELLED;
import static com.susukkang.fgc.common.code.ContractStatus.LAPSED;
import static com.susukkang.fgc.common.code.ContractStatus.REVIVED;
import static com.susukkang.fgc.common.code.ContractStatus.UNPAID;
import static com.susukkang.fgc.contract.dto.ContractStatusEffect.EntityType.PAYMENT;
import static com.susukkang.fgc.contract.dto.ContractStatusEffect.EntityType.SCHEDULE_LINE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/**
 * FUN-015 상태별 지급조건 판단표(인터페이스정의서 §9-5, 운영정책서 제48조). 기대값은 지급예정일·귀속일 시점 상태로 손으로 계산했다.
 */
class ContractStatusPaymentRuleTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Long POLICY = 77L;

    @Test
    void lapseStopsOnlyUnconfirmedFutureItemsAndExplainsWhatItKeeps() {
        StatusPoint active = at("2026-01-01", 1, ACTIVE);
        StatusPoint lapsed = at("2026-09-01", 2, LAPSED);
        List<Line> lines = List.of(
                line(1, "2026-08-25", ScheduleLineStatus.PLANNED),   // 실효 전 회차: 그대로 지급 대상
                line(2, "2026-09-25", ScheduleLineStatus.PLANNED),
                line(3, "2026-10-25", ScheduleLineStatus.CONFIRMED),
                lockedMonth(4, "2026-11-25"),
                line(5, "2026-09-01", ScheduleLineStatus.PLANNED));  // 효력일 당일이 지급예정일이면 그날 끝 상태(실효)
        List<Payment> payments = List.of(
                payment(10, CommissionPaymentStatus.DRAFT, "2026-08-20"),
                payment(11, CommissionPaymentStatus.DRAFT, "2026-09-20"),
                payment(12, CommissionPaymentStatus.CONFIRMED, "2026-09-20"),
                payment(13, CommissionPaymentStatus.CANCELLED, "2026-09-20"),
                payment(14, CommissionPaymentStatus.DRAFT, "2026-08-20", "2026-09-20")); // 귀속일 하나라도 중단이면 보류

        List<ContractStatusEffect> effects = ContractStatusPaymentRule.judge(
                List.of(active, lapsed), lapsed, POLICY, lines, payments, Set.of());

        assertThat(effects).extracting(ContractStatusEffect::entityType, ContractStatusEffect::entityId,
                        ContractStatusEffect::previousState, ContractStatusEffect::nextState,
                        ContractStatusEffect::amount, ContractStatusEffect::policyVersionId,
                        ContractStatusEffect::reasonCode)
                .containsExactly(
                        tuple(SCHEDULE_LINE, 2L, "PLANNED", "CANCELLED", new BigDecimal("1000"), POLICY, "STATUS_INACTIVE"),
                        tuple(SCHEDULE_LINE, 3L, "CONFIRMED", "EXCLUDED", new BigDecimal("1000"), POLICY, "ALREADY_CONFIRMED"),
                        tuple(SCHEDULE_LINE, 4L, "PLANNED", "EXCLUDED", new BigDecimal("1000"), POLICY, "MONTH_LOCKED"),
                        tuple(SCHEDULE_LINE, 5L, "PLANNED", "CANCELLED", new BigDecimal("1000"), POLICY, "STATUS_INACTIVE"),
                        tuple(PAYMENT, 11L, "DRAFT", "HELD", new BigDecimal("5000"), POLICY, "STATUS_INACTIVE"),
                        tuple(PAYMENT, 12L, "CONFIRMED", "EXCLUDED", new BigDecimal("5000"), POLICY, "ALREADY_CONFIRMED"),
                        tuple(PAYMENT, 14L, "DRAFT", "HELD", new BigDecimal("5000"), POLICY, "STATUS_INACTIVE"));
        assertThat(effects).allSatisfy(effect -> assertThat(effect.reason()).isNotBlank());
        assertThat(effects.get(0).reason()).contains("2026-09-25", "실효");
    }

    @Test
    void unpaidHoldsUntilPaymentResumesAndResumptionReleasesTheHeldItems() {
        StatusPoint active = at("2026-01-01", 1, ACTIVE);
        StatusPoint unpaid = at("2026-09-01", 2, UNPAID);
        StatusPoint paid = at("2026-10-10", 3, ACTIVE);

        List<ContractStatusEffect> onUnpaid = ContractStatusPaymentRule.judge(List.of(active, unpaid), unpaid, POLICY,
                List.of(line(1, "2026-09-25", ScheduleLineStatus.PLANNED),
                        line(2, "2026-10-25", ScheduleLineStatus.PLANNED)),
                List.of(payment(10, CommissionPaymentStatus.DRAFT, "2026-09-20")), Set.of());
        assertThat(onUnpaid).extracting(ContractStatusEffect::entityId, ContractStatusEffect::nextState,
                        ContractStatusEffect::reasonCode)
                .containsExactly(tuple(1L, "HOLD", "STATUS_UNPAID"), tuple(2L, "HOLD", "STATUS_UNPAID"),
                        tuple(10L, "HELD", "STATUS_UNPAID"));

        List<ContractStatusEffect> onPayment = ContractStatusPaymentRule.judge(List.of(active, unpaid, paid), paid, POLICY,
                List.of(line(1, "2026-09-25", ScheduleLineStatus.HOLD),
                        line(2, "2026-10-25", ScheduleLineStatus.HOLD)),
                List.of(payment(10, CommissionPaymentStatus.DRAFT, "2026-09-20")), Set.of(10L));
        assertThat(onPayment).extracting(ContractStatusEffect::entityId, ContractStatusEffect::previousState,
                        ContractStatusEffect::nextState, ContractStatusEffect::reasonCode)
                .containsExactly(tuple(1L, "HOLD", "PLANNED", "UNPAID_RESUMED"),
                        tuple(2L, "HOLD", "PLANNED", "STATUS_ACTIVE"),
                        tuple(10L, "DRAFT", "RELEASED", "UNPAID_RESUMED"));
    }

    @Test
    void unpaidPeriodEndingInLapseStopsTheHeldItems() {
        StatusPoint active = at("2026-01-01", 1, ACTIVE);
        StatusPoint unpaid = at("2026-09-01", 2, UNPAID);
        StatusPoint lapsed = at("2026-10-01", 3, LAPSED);

        List<ContractStatusEffect> effects = ContractStatusPaymentRule.judge(List.of(active, unpaid, lapsed), lapsed, POLICY,
                List.of(line(1, "2026-09-25", ScheduleLineStatus.HOLD),
                        line(2, "2026-10-25", ScheduleLineStatus.HOLD)),
                List.of(payment(10, CommissionPaymentStatus.DRAFT, "2026-09-20"),
                        payment(11, CommissionPaymentStatus.DRAFT, "2026-09-21")), Set.of(10L));

        assertThat(effects).extracting(ContractStatusEffect::entityId, ContractStatusEffect::nextState,
                        ContractStatusEffect::reasonCode)
                .containsExactly(tuple(1L, "CANCELLED", "UNPAID_ENDED"), tuple(2L, "CANCELLED", "STATUS_INACTIVE"),
                        tuple(11L, "HELD", "UNPAID_ENDED"));
    }

    @Test
    void revivalResumesOnlyFutureItemsWithoutBackfillingTheLapsedPeriod() {
        StatusPoint active = at("2026-01-01", 1, ACTIVE);
        StatusPoint lapsed = at("2026-09-01", 2, LAPSED);
        StatusPoint revived = at("2026-10-15", 3, REVIVED);

        List<ContractStatusEffect> effects = ContractStatusPaymentRule.judge(List.of(active, lapsed, revived), revived,
                POLICY,
                List.of(line(1, "2026-09-25", ScheduleLineStatus.CANCELLED),
                        line(2, "2026-10-14", ScheduleLineStatus.CANCELLED),
                        line(3, "2026-10-15", ScheduleLineStatus.CANCELLED),
                        line(4, "2026-10-25", ScheduleLineStatus.CANCELLED),
                        lockedMonth(5, "2026-09-30")),
                List.of(payment(10, CommissionPaymentStatus.DRAFT, "2026-09-20"),
                        payment(11, CommissionPaymentStatus.DRAFT, "2026-10-20"),
                        payment(12, CommissionPaymentStatus.CONFIRMED, "2026-09-21")), Set.of(10L, 11L));

        // NO_RETROACTIVE: 실효 기간(9/1~10/14) 회차·지급 건은 보류·중단 그대로, 부활일부터만 재개한다.
        // 확정·월잠금 대상의 제외 사유는 실효 사건에서 이미 남았고 부활이 판단을 바꾸지 않으므로 다시 남기지 않는다.
        assertThat(effects).extracting(ContractStatusEffect::entityType, ContractStatusEffect::entityId,
                        ContractStatusEffect::previousState, ContractStatusEffect::nextState,
                        ContractStatusEffect::reasonCode)
                .containsExactly(tuple(SCHEDULE_LINE, 3L, "CANCELLED", "PLANNED", "STATUS_ACTIVE"),
                        tuple(SCHEDULE_LINE, 4L, "CANCELLED", "PLANNED", "STATUS_ACTIVE"),
                        tuple(PAYMENT, 11L, "DRAFT", "RELEASED", "STATUS_ACTIVE"));
    }

    @Test
    void withdrawalVoidsItemsDueBeforeItsEffectiveDate() {
        StatusPoint active = at("2026-01-01", 1, ACTIVE);
        StatusPoint withdrawn = at("2026-01-10", 2, CANCELLED);

        List<ContractStatusEffect> effects = ContractStatusPaymentRule.judge(List.of(active, withdrawn), withdrawn, POLICY,
                List.of(line(1, "2026-01-05", ScheduleLineStatus.PLANNED)),
                List.of(payment(10, CommissionPaymentStatus.DRAFT, "2026-01-03")), Set.of());

        assertThat(effects).extracting(ContractStatusEffect::entityId, ContractStatusEffect::nextState,
                        ContractStatusEffect::reasonCode)
                .containsExactly(tuple(1L, "CANCELLED", "CONTRACT_WITHDRAWN"), tuple(10L, "HELD", "CONTRACT_WITHDRAWN"));
    }

    @Test
    void decisionForAnEventIgnoresEventsRecordedAfterIt() {
        StatusPoint active = at("2026-01-01", 1, ACTIVE);
        StatusPoint lapsed = at("2026-09-01", 2, LAPSED);
        StatusPoint revived = at("2026-10-15", 3, REVIVED);
        List<Line> lines = List.of(line(1, "2026-10-25", ScheduleLineStatus.PLANNED));

        List<ContractStatusEffect> immediately = ContractStatusPaymentRule.judge(
                List.of(active, lapsed), lapsed, POLICY, lines, List.of(), Set.of());
        List<ContractStatusEffect> later = ContractStatusPaymentRule.judge(
                List.of(active, lapsed, revived), lapsed, POLICY, lines, List.of(), Set.of());

        assertThat(later).isEqualTo(immediately);
        assertThat(later).extracting(ContractStatusEffect::nextState).containsExactly("CANCELLED");
    }

    @Test
    void itemsBeforeAnyRecordedStatusStayPayable() {
        StatusPoint lapsed = at("2026-09-01", 1, LAPSED);

        List<ContractStatusEffect> effects = ContractStatusPaymentRule.judge(List.of(lapsed), lapsed, POLICY,
                List.of(line(1, "2026-08-25", ScheduleLineStatus.PLANNED),
                        line(2, "2026-09-25", ScheduleLineStatus.PLANNED)),
                List.of(payment(10, CommissionPaymentStatus.DRAFT, "2026-08-20")), Set.of());

        assertThat(effects).extracting(ContractStatusEffect::entityId).containsExactly(2L);
    }

    @Test
    void sameEffectiveTimeIsOrderedByEventSequence() {
        StatusPoint active = at("2026-01-01", 1, ACTIVE);
        StatusPoint lapsed = new StatusPoint(LocalDate.parse("2026-09-01").atTime(LocalTime.of(10, 0))
                .atZone(SEOUL).toOffsetDateTime(), 2, LAPSED);
        StatusPoint revived = new StatusPoint(lapsed.effectiveAt(), 3, REVIVED);

        List<ContractStatusEffect> effects = ContractStatusPaymentRule.judge(List.of(active, revived, lapsed), revived,
                POLICY, List.of(line(1, "2026-09-25", ScheduleLineStatus.CANCELLED)), List.of(), Set.of());

        assertThat(effects).extracting(ContractStatusEffect::nextState).containsExactly("PLANNED");
    }

    private static StatusPoint at(String effectiveDate, int eventSeq, ContractStatus status) {
        return new StatusPoint(LocalDate.parse(effectiveDate).atStartOfDay(SEOUL).toOffsetDateTime(), eventSeq, status);
    }

    @Test
    void revivalNonPayableEvidenceExcludesLockedAndConfirmedLines() {
        // FUN-029: 월잠금·확정은 NO_RETROACTIVE 근거 생성에서도 보존 대상으로 제외한다.
        StatusPoint active = at("2026-01-01", 1, ACTIVE);
        StatusPoint lapse = at("2026-09-01", 2, LAPSED);
        StatusPoint revival = at("2026-10-15", 3, REVIVED);
        List<ContractStatusEffect> effects = ContractStatusPaymentRule.judgeNotPayable(
                List.of(active, lapse, revival), revival, POLICY,
                List.of(lockedMonth(1, "2026-09-20"), line(2, "2026-09-20", ScheduleLineStatus.CONFIRMED),
                        line(3, "2026-09-20", ScheduleLineStatus.CANCELLED)), List.of());
        assertThat(effects).extracting(ContractStatusEffect::entityId, ContractStatusEffect::reasonCode)
                .containsExactly(tuple(3L, "NO_RETROACTIVE"));
    }

    private static Line line(long id, String dueDate, ScheduleLineStatus status) {
        return new Line(id, LocalDate.parse(dueDate), status, ScheduleHeaderStatus.PLANNED, new BigDecimal("1000"), false);
    }

    private static Line lockedMonth(long id, String dueDate) {
        return new Line(id, LocalDate.parse(dueDate), ScheduleLineStatus.PLANNED, ScheduleHeaderStatus.PLANNED,
                new BigDecimal("1000"), true);
    }

    private static Payment payment(long id, CommissionPaymentStatus status, String... attributionDates) {
        return new Payment(id, status, new BigDecimal("5000"),
                Arrays.stream(attributionDates).map(LocalDate::parse).toList());
    }
}
