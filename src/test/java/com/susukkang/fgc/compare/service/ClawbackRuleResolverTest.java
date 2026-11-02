package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.susukkang.fgc.compare.service.ClawbackRuleResolver.Resolution;
import com.susukkang.fgc.compare.service.ClawbackRuleResolver.RuleRow;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 환수율 선택은 FUN-055(D-02, 운영정책서 제48조의3)와 같은 기준이어야 한다. */
class ClawbackRuleResolverTest {

    private static final long BASE = 11L;
    private static final long MAINTENANCE = 12L;

    private static RuleRow rule(long policy, Long item, boolean insurer, boolean offering, int from, int to, String pct) {
        return new RuleRow(policy, "CLAW v" + policy, item, insurer, offering, from, to, ClawbackRuleResolver.BASIS_CODE,
                new BigDecimal(pct), "{}");
    }

    @Test
    @DisplayName("적용되는 환수 정책이 없으면 환수율 0원으로 계산하지 않고 제외한다")
    void noRuleMeansExcluded() {
        Resolution resolution = ClawbackRuleResolver.resolve(List.of(), List.of(BASE), 11);

        assertThat(resolution.excludeReason()).isEqualTo("CLAWBACK_RATE_MISSING");
    }

    @Test
    @DisplayName("12차월 해지(경과 11개월)는 0~11개월 구간, 13차월 해지(경과 12개월)는 다음 구간을 쓴다")
    void elapsedMonthPicksTheBand() {
        List<RuleRow> rows = List.of(rule(1, BASE, false, false, 0, 11, "100"), rule(1, BASE, false, false, 12, 23, "50"));

        assertThat(ClawbackRuleResolver.resolve(rows, List.of(BASE), 11).ratesByItem().get(BASE)).isEqualByComparingTo("100");
        assertThat(ClawbackRuleResolver.resolve(rows, List.of(BASE), 12).ratesByItem().get(BASE)).isEqualByComparingTo("50");
    }

    @Test
    @DisplayName("정책에 규칙이 없는 수수료 항목은 환수 대상이 아니다(환수율 null)")
    void itemWithoutRuleIsNotATarget() {
        Resolution resolution = ClawbackRuleResolver.resolve(List.of(rule(1, BASE, false, false, 0, 1199, "100")),
                List.of(BASE, MAINTENANCE), 11);

        assertThat(resolution.excludeReason()).isNull();
        assertThat(resolution.ratesByItem()).containsKey(MAINTENANCE);
        assertThat(resolution.ratesByItem().get(MAINTENANCE)).isNull();
        assertThat(resolution.policyVersion()).isEqualTo("CLAW v1");
    }

    @Test
    @DisplayName("규칙이 있는 항목인데 경과월 구간이 비면 근거 부족으로 제외한다")
    void uncoveredBandIsExcluded() {
        Resolution resolution = ClawbackRuleResolver.resolve(List.of(rule(1, BASE, false, false, 12, 23, "50")), List.of(BASE), 11);

        assertThat(resolution.excludeReason()).isEqualTo("CLAWBACK_RATE_MISSING");
    }

    @Test
    @DisplayName("가장 구체적인 규칙(항목 > 판매버전 > 보험사)을 쓴다")
    void mostSpecificRuleWins() {
        List<RuleRow> rows = List.of(rule(1, null, false, false, 0, 11, "100"), rule(1, null, true, true, 0, 11, "70"),
                rule(1, BASE, false, false, 0, 11, "30"));

        assertThat(ClawbackRuleResolver.resolve(rows, List.of(BASE, MAINTENANCE), 11).ratesByItem())
                .satisfies(rates -> {
                    assertThat(rates.get(BASE)).isEqualByComparingTo("30");
                    assertThat(rates.get(MAINTENANCE)).isEqualByComparingTo("70");
                });
    }

    @Test
    @DisplayName("같은 구체성의 규칙이 겹치거나 같은 범위의 정책이 둘 이상이면 임의로 고르지 않고 제외한다")
    void ambiguityIsExcluded() {
        List<RuleRow> overlap = List.of(rule(1, BASE, false, false, 0, 11, "100"), rule(1, BASE, false, false, 6, 17, "50"));
        List<RuleRow> twoGeneral = List.of(rule(1, BASE, false, false, 0, 11, "100"), rule(2, BASE, false, false, 0, 11, "80"));
        List<RuleRow> twoInsurer = List.of(rule(1, BASE, true, false, 0, 11, "60"), rule(2, BASE, true, false, 0, 11, "80"));

        assertThat(ClawbackRuleResolver.resolve(overlap, List.of(BASE), 11).excludeReason()).isEqualTo("CLAWBACK_RULE_AMBIGUOUS");
        assertThat(ClawbackRuleResolver.resolve(twoGeneral, List.of(BASE), 11).excludeReason()).isEqualTo("CLAWBACK_RULE_AMBIGUOUS");
        assertThat(ClawbackRuleResolver.resolve(twoInsurer, List.of(BASE), 11).excludeReason()).isEqualTo("CLAWBACK_RULE_AMBIGUOUS");
    }

    @Test
    @DisplayName("보험사 전용 정책이 전체 정책보다, 판매버전 전용 정책이 보험사 전용보다 앞선다 — 고른 정책 안에서만 항목을 푼다")
    void narrowerPolicyWins() {
        RuleRow general = rule(1, BASE, false, false, 0, 11, "100");
        RuleRow generalIncentive = rule(1, MAINTENANCE, false, false, 0, 11, "100");
        RuleRow insurer = rule(2, BASE, true, false, 0, 11, "40");
        RuleRow offering = rule(3, BASE, false, true, 0, 11, "20");

        Resolution insurerWins = ClawbackRuleResolver.resolve(List.of(general, generalIncentive, insurer), List.of(BASE, MAINTENANCE), 11);
        assertThat(insurerWins.policyVersion()).isEqualTo("CLAW v2");
        assertThat(insurerWins.ratesByItem().get(BASE)).isEqualByComparingTo("40");
        // 고른 보험사 정책에 이 항목 규칙이 없으면 전체 정책의 규칙을 섞지 않고 환수 대상이 아니다(D-02 5항)
        assertThat(insurerWins.ratesByItem().get(MAINTENANCE)).isNull();

        Resolution offeringWins = ClawbackRuleResolver.resolve(List.of(general, insurer, offering), List.of(BASE), 11);
        assertThat(offeringWins.policyVersion()).isEqualTo("CLAW v3");
        assertThat(offeringWins.ratesByItem().get(BASE)).isEqualByComparingTo("20");
    }

    @Test
    @DisplayName("FUN-055 가 계산하지 않는 기준금액 코드나 조건식이 있는 규칙은 제외한다")
    void unsupportedBasisOrConditionIsExcluded() {
        RuleRow basis = new RuleRow(1, "CLAW v1", BASE, false, false, 0, 11, "REMAINING_PREMIUM", BigDecimal.TEN, "{}");
        RuleRow condition = new RuleRow(1, "CLAW v1", BASE, false, false, 0, 11, ClawbackRuleResolver.BASIS_CODE, BigDecimal.TEN,
                "{\"fault\": \"AGENT\"}");

        assertThat(ClawbackRuleResolver.resolve(List.of(basis), List.of(BASE), 11).excludeReason())
                .isEqualTo("CLAWBACK_RULE_UNSUPPORTED");
        assertThat(ClawbackRuleResolver.resolve(List.of(condition), List.of(BASE), 11).excludeReason())
                .isEqualTo("CLAWBACK_RULE_UNSUPPORTED");
    }
}
