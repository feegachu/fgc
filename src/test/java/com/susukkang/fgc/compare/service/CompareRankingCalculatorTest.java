package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.susukkang.fgc.compare.service.CompareRankingCalculator.CapRule;
import com.susukkang.fgc.compare.service.CompareRankingCalculator.Candidate;
import com.susukkang.fgc.compare.service.CompareRankingCalculator.Result;
import com.susukkang.fgc.compare.service.CompareRankingCalculator.Rule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompareRankingCalculatorTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 7, 31);
    private static final CapRule CAP_1200 = new CapRule(new BigDecimal("12"), "REG-CAP-GA-2026-V1 v1");

    private static Rule rate(int from, int to, String pct) {
        return new Rule(from, to, new BigDecimal(pct), null, "현행수수료", "GA-CUR-2026-V1 v1");
    }

    /** 환수율이 있는 수수료 규칙. 환수율은 환수 규칙(ClawbackRuleResolver)이 항목마다 고른 값이다. */
    private static Rule rate(int from, int to, String pct, String clawbackPct) {
        return new Rule(from, to, new BigDecimal(pct), null, "현행수수료", "GA-CUR-2026-V1 v1", false, new BigDecimal(clawbackPct));
    }

    private static Candidate candidate(long id, String insurer, CapRule cap, Rule... rules) {
        return new Candidate(id, insurer, "상품" + id, List.of(rules), "CLAW-TEST v1", cap, null);
    }

    private static Result calc(Candidate candidate, long base) {
        return CompareRankingCalculator.calculate(candidate, base, AS_OF);
    }

    @Test
    @DisplayName("지급행(회차) 단위로 HALF_UP 한 뒤 합산한다")
    void roundsEachPaymentRowBeforeSumming() {
        // 10,005원 × 0.05% = 5.0025원 → 행마다 5원, 2~12회차 11행 합 55원
        Result result = calc(candidate(1, "A", null, rate(1, 1, "100"), rate(2, 12, "0.05")), 10_005);

        assertThat(result.grossReceipt()).isEqualTo(10_005 + 11 * 5);
    }

    @Test
    @DisplayName("행마다 반올림하면 합계를 한 번에 반올림한 값과 달라질 수 있다")
    void perRowRoundingDiffersFromTotalRounding() {
        // 150원 × 0.5% = 0.75원 → 행마다 1원 → 11행 11원 (합계를 먼저 계산하면 8.25 → 8원)
        Result result = calc(candidate(1, "A", null, rate(2, 12, "0.5")), 150);

        assertThat(result.grossReceipt()).isEqualTo(11);
    }

    @Test
    @DisplayName("12회차를 넘는 구간은 수령 예상액에 넣지 않는다")
    void ignoresInstallmentsBeyondTwelve() {
        Result result = calc(candidate(1, "A", null, rate(1, 24, "10")), 100_000);

        assertThat(result.grossReceipt()).isEqualTo(12 * 10_000);
    }

    @Test
    @DisplayName("환수 노출액은 지급행 금액 × 그 항목의 환수율을 행마다 반올림해 합한다(D-01 정답 예시 1)")
    void clawbackIsRoundedPerRow() {
        Result result = calc(candidate(1, "A", null, rate(1, 1, "900", "34.8")), 100_000);

        assertThat(result.grossReceipt()).isEqualTo(900_000);
        assertThat(result.clawbackExposure()).isEqualTo(313_200);
        assertThat(result.conservativeNet()).isEqualTo(586_800);
    }

    @Test
    @DisplayName("환수 규칙이 없는 수수료 항목은 환수 대상이 아니라 0원이고, 근거에 대상 아님을 남긴다")
    void itemWithoutClawbackRuleIsNotClawedBack() {
        // 기본수수료 900%는 환수율 100%, 유지수수료 2~12회차 5%는 그 정책의 환수 대상이 아니다
        Result result = calc(candidate(1, "A", null, rate(1, 1, "900", "100"), rate(2, 12, "5")), 100_000);

        assertThat(result.grossReceipt()).isEqualTo(900_000 + 11 * 5_000);
        assertThat(result.clawbackExposure()).isEqualTo(900_000);
        assertThat(result.conservativeNet()).isEqualTo(55_000);
        assertThat(result.evidence()).anyMatch(e -> e.label().equals("환수 노출액") && e.rule().contains("2~12회차")
                && e.rule().contains("환수 규칙 대상 아님") && e.value() == null);
    }

    @Test
    @DisplayName("수수료 항목마다 환수율이 다르면 항목별로 계산해 합한다")
    void clawbackRateIsAppliedPerItem() {
        Result result = calc(candidate(1, "A", null, rate(1, 1, "650", "100"), rate(1, 1, "100", "50")), 100_000);

        assertThat(result.clawbackExposure()).isEqualTo(650_000 + 50_000);
        assertThat(result.evidence()).filteredOn(e -> e.label().equals("환수 노출액"))
                .extracting(e -> e.rule() + "=" + e.value())
                .containsExactly("현행수수료 1~1회차 · 한도 반영 지급액 × 환수율 100%=650000",
                        "현행수수료 1~1회차 · 한도 반영 지급액 × 환수율 50%=50000");
    }

    @Test
    @DisplayName("한도 안이면 그대로 지급하고 한도 영향은 반올림하지 않은 비율이다(D-01 정답 예시 3)")
    void withinCapPaysEverything() {
        // 805% × 100,000원 = 805,000원, 한도 1,200% = 1,200,000원 → 67.083333%(화면 67.0%)
        Result result = calc(candidate(1, "A", CAP_1200, rate(1, 1, "650"), rate(2, 12, "5"), rate(1, 1, "100")), 100_000);

        assertThat(result.grossReceipt()).isEqualTo(805_000);
        assertThat(result.cappedReceipt()).isEqualTo(805_000);
        assertThat(result.carryover()).isZero();
        assertThat(result.capImpactPct()).isEqualTo(67.083333);
    }

    @Test
    @DisplayName("99.96%는 100.0%로 올리지 않는다 — 이월이 없는 상품이 초과로 보이지 않게 한다")
    void capImpactIsNotRoundedUpToTheLimit() {
        // 한도 1,200,000원에 산입 1,199,520원(1회차 1,199.52%) → 99.96%, 이월 0원
        Result result = calc(candidate(1, "A", CAP_1200, rate(1, 1, "1199.52")), 100_000);

        assertThat(result.capImpactPct()).isEqualTo(99.96);
        assertThat(result.carryover()).isZero();
    }

    @Test
    @DisplayName("한도를 넘는 부분은 회차 순서대로 잘라 이월 예정액으로 따로 센다(D-01 정답 예시 2)")
    void overCapCarriesOverInInstallmentOrder() {
        // 첫 회차 1,000% + 2~12회차 매월 30% (11행) = 1,330% → 한도 1,200% 를 넘는 130% 는 뒤 회차에서 잘린다
        Result result = calc(candidate(1, "A", CAP_1200, rate(1, 1, "1000", "10"), rate(2, 12, "30", "10")), 100_000);

        assertThat(result.grossReceipt()).isEqualTo(1_330_000);
        assertThat(result.cappedReceipt()).isEqualTo(1_200_000);
        assertThat(result.carryover()).isEqualTo(130_000);
        assertThat(result.capImpactPct()).isEqualTo(110.833333);
        // 환수는 실제 지급되는 금액 기준이다: 1,200,000 × 10%
        assertThat(result.clawbackExposure()).isEqualTo(120_000);
        assertThat(result.conservativeNet()).isEqualTo(1_080_000);
    }

    @Test
    @DisplayName("한도 규칙이 없으면 한도를 반영하지 않고 한도 영향은 비운다")
    void missingCapRuleMeansNoCap() {
        Result result = calc(candidate(1, "A", null, rate(1, 1, "2000")), 100_000);

        assertThat(result.cappedReceipt()).isEqualTo(2_000_000);
        assertThat(result.capImpactPct()).isNull();
        assertThat(result.evidence()).anyMatch(e -> e.label().equals("한도 영향") && e.rule().contains("자료 없음"));
    }

    @Test
    @DisplayName("근거 항목마다 정책 버전과 기준일을 싣고, 환수 근거는 해약환급률표가 아니라 환수 규칙이다")
    void evidenceCarriesPolicyVersionAndAsOf() {
        Result result = calc(candidate(1, "A", CAP_1200, rate(1, 1, "100", "10")), 100_000);

        assertThat(result.evidence()).allSatisfy(e -> assertThat(e.asOf()).isEqualTo(AS_OF));
        assertThat(result.evidence()).extracting(e -> e.source() + ":" + e.policyVersion()).contains(
                "수수료 규칙:GA-CUR-2026-V1 v1", "한도 규칙:REG-CAP-GA-2026-V1 v1", "환수 규칙:CLAW-TEST v1");
        assertThat(result.evidence()).noneMatch(e -> e.source().contains("해약환급률") || e.rule().contains("해약환급률"));
    }

    @Test
    @DisplayName("보수적 순수령액이 큰 순서로 정렬하고 동률이면 환수가 작은 쪽, 그래도 같으면 상품 ID 순이다(D-01 정답 예시 4)")
    void ranksByNetThenClawbackThenOfferingId() {
        List<Result> ranked = CompareRankingCalculator.rank(List.of(
                candidate(1, "가보험", null, rate(1, 1, "900", "34.8")),
                candidate(2, "라보험", null, rate(1, 1, "900", "24.0")),
                candidate(3, "다보험", null, rate(1, 1, "760", "10.0")),
                candidate(4, "나보험", null, rate(1, 1, "900", "24.0"))), 100_000, AS_OF);

        // 2·3·4 는 순수령액 684,000 동률 → 환수가 가장 작은 3, 다음 환수 동률인 2·4 는 보험사명과 무관하게 상품 ID 순, 마지막 1
        assertThat(ranked).extracting(r -> r.candidate().offeringId()).containsExactly(3L, 2L, 4L, 1L);
    }

    @Test
    @DisplayName("정액 규칙은 기준 보험료와 무관하게 회차마다 정액을 더한다")
    void fixedAmountRule() {
        Rule fixed = new Rule(1, 3, null, new BigDecimal("1000.4"), "정액", "GA-CUR-2026-V1 v1");

        Result result = calc(candidate(1, "A", null, fixed), 50_000);

        assertThat(result.grossReceipt()).isEqualTo(3_000);
    }

    @Test
    @DisplayName("한도 산입 제외 항목은 전액 지급하되 한도를 소진하지 않는다")
    void exemptItemDoesNotConsumeCap() {
        // 산입 항목 1회차 1,000% + 제외 항목 2회차 500%, 한도 12배(기준 100,000원 → 1,200,000원)
        Rule included = rate(1, 1, "1000");
        Rule exempt = new Rule(2, 2, new BigDecimal("500"), null, "제외항목", "GA-CUR-2026-V1 v1", true, null);
        Rule overflow = rate(3, 3, "300");

        Result result = calc(candidate(1, "A", CAP_1200, included, exempt, overflow), 100_000);

        // 한도 안에서 산입분은 1,000,000 + 200,000(3회차 일부), 제외분 500,000은 그대로 지급, 3회차 100,000은 이월
        assertThat(result.grossReceipt()).isEqualTo(1_800_000);
        assertThat(result.cappedReceipt()).isEqualTo(1_700_000);
        assertThat(result.carryover()).isEqualTo(100_000);
        // 한도 영향은 산입분만 본다: 1,300,000 / 1,200,000
        assertThat(result.capImpactPct()).isEqualTo(108.333333);
    }

    @Test
    @DisplayName("보험사 수 경고는 화면에 나가는 상위 5개 기준이다 — 6위 이하의 다른 보험사는 세지 않는다")
    void insurerWarningCountsOnlyTheShownTopFive() {
        // 순위 1~5: A사 3개·B사 2개, 6~7위: C사 — 전체로는 3개사지만 표시 목록은 2개사다
        List<Result> ranked = CompareRankingCalculator.rank(List.of(
                candidate(1, "A", null, rate(1, 1, "70")), candidate(2, "A", null, rate(1, 1, "60")),
                candidate(3, "A", null, rate(1, 1, "50")), candidate(4, "B", null, rate(1, 1, "40")),
                candidate(5, "B", null, rate(1, 1, "30")), candidate(6, "C", null, rate(1, 1, "20")),
                candidate(7, "C", null, rate(1, 1, "10"))), 100_000, AS_OF);

        assertThat(CompareRankingService.fewInsurers(ranked)).isFalse();
        assertThat(CompareRankingService.shownOf(ranked)).hasSize(5);
        assertThat(CompareRankingService.fewInsurers(CompareRankingService.shownOf(ranked))).isTrue();
    }
}
