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

    private static Candidate candidate(long id, String insurer, String refund, CapRule cap, Rule... rules) {
        return new Candidate(id, insurer, "상품" + id, List.of(rules), refund == null ? null : new BigDecimal(refund),
                refund == null ? null : "INS-REFUND-2026-V1 v1", cap, null);
    }

    private static Result calc(Candidate candidate, long base) {
        return CompareRankingCalculator.calculate(candidate, base, AS_OF);
    }

    @Test
    @DisplayName("지급행(회차) 단위로 HALF_UP 한 뒤 합산한다")
    void roundsEachPaymentRowBeforeSumming() {
        // 10,005원 × 0.05% = 5.0025원 → 행마다 5원, 2~12회차 11행 합 55원
        Result result = calc(candidate(1, "A", null, null, rate(1, 1, "100"), rate(2, 12, "0.05")), 10_005);

        assertThat(result.grossReceipt()).isEqualTo(10_005 + 11 * 5);
    }

    @Test
    @DisplayName("행마다 반올림하면 합계를 한 번에 반올림한 값과 달라질 수 있다")
    void perRowRoundingDiffersFromTotalRounding() {
        // 150원 × 0.5% = 0.75원 → 행마다 1원 → 11행 11원 (합계를 먼저 계산하면 8.25 → 8원)
        Result result = calc(candidate(1, "A", null, null, rate(2, 12, "0.5")), 150);

        assertThat(result.grossReceipt()).isEqualTo(11);
    }

    @Test
    @DisplayName("12회차를 넘는 구간은 수령 예상액에 넣지 않는다")
    void ignoresInstallmentsBeyondTwelve() {
        Result result = calc(candidate(1, "A", null, null, rate(1, 24, "10")), 100_000);

        assertThat(result.grossReceipt()).isEqualTo(12 * 10_000);
    }

    @Test
    @DisplayName("환수 노출액은 지급행 금액 × 환수율을 행마다 반올림해 합한다")
    void clawbackIsRoundedPerRow() {
        Result result = calc(candidate(1, "A", "34.8", null, rate(1, 1, "900")), 100_000);

        assertThat(result.grossReceipt()).isEqualTo(900_000);
        assertThat(result.clawbackExposure()).isEqualTo(313_200);
        assertThat(result.conservativeNet()).isEqualTo(586_800);
    }

    @Test
    @DisplayName("환급률표가 없으면 환수 0원으로 계산하고 근거에 자료 없음을 남긴다")
    void missingRefundTableMeansZeroClawback() {
        Result result = calc(candidate(1, "A", null, null, rate(1, 1, "900")), 100_000);

        assertThat(result.clawbackExposure()).isZero();
        assertThat(result.evidence()).anyMatch(e -> e.label().equals("환수 노출액") && e.rule().contains("자료 없음"));
    }

    @Test
    @DisplayName("한도 안이면 그대로 지급하고 한도 영향은 한도 대비 비율이다")
    void withinCapPaysEverything() {
        // 805% × 100,000원 = 805,000원, 한도 1,200% = 1,200,000원 → 67.1%
        Result result = calc(candidate(1, "A", "10", CAP_1200, rate(1, 1, "650"), rate(2, 12, "5"), rate(1, 1, "100")), 100_000);

        assertThat(result.grossReceipt()).isEqualTo(805_000);
        assertThat(result.cappedReceipt()).isEqualTo(805_000);
        assertThat(result.carryover()).isZero();
        assertThat(result.capImpactPct()).isEqualTo(67.1);
    }

    @Test
    @DisplayName("한도를 넘는 부분은 회차 순서대로 잘라 이월 예정액으로 따로 센다")
    void overCapCarriesOverInInstallmentOrder() {
        // 첫 회차 1,000% + 2~12회차 매월 30% (11행) = 1,330% → 한도 1,200% 를 넘는 130% 는 뒤 회차에서 잘린다
        Result result = calc(candidate(1, "A", "10", CAP_1200, rate(1, 1, "1000"), rate(2, 12, "30")), 100_000);

        assertThat(result.grossReceipt()).isEqualTo(1_330_000);
        assertThat(result.cappedReceipt()).isEqualTo(1_200_000);
        assertThat(result.carryover()).isEqualTo(130_000);
        assertThat(result.capImpactPct()).isEqualTo(110.8);
        // 환수는 실제 지급되는 금액 기준이다: 1,200,000 × 10%
        assertThat(result.clawbackExposure()).isEqualTo(120_000);
        assertThat(result.conservativeNet()).isEqualTo(1_080_000);
    }

    @Test
    @DisplayName("한도 규칙이 없으면 한도를 반영하지 않고 한도 영향은 비운다")
    void missingCapRuleMeansNoCap() {
        Result result = calc(candidate(1, "A", null, null, rate(1, 1, "2000")), 100_000);

        assertThat(result.cappedReceipt()).isEqualTo(2_000_000);
        assertThat(result.capImpactPct()).isNull();
        assertThat(result.evidence()).anyMatch(e -> e.label().equals("한도 영향") && e.rule().contains("자료 없음"));
    }

    @Test
    @DisplayName("근거 항목마다 정책 버전과 기준일을 싣는다")
    void evidenceCarriesPolicyVersionAndAsOf() {
        Result result = calc(candidate(1, "A", "10", CAP_1200, rate(1, 1, "100")), 100_000);

        assertThat(result.evidence()).allSatisfy(e -> assertThat(e.asOf()).isEqualTo(AS_OF));
        assertThat(result.evidence()).extracting(e -> e.source() + ":" + e.policyVersion()).contains(
                "수수료 규칙:GA-CUR-2026-V1 v1", "한도 규칙:REG-CAP-GA-2026-V1 v1", "해약환급률표:INS-REFUND-2026-V1 v1");
    }

    @Test
    @DisplayName("보수적 순수령액이 큰 순서로 정렬하고 동률이면 환수가 작은 쪽이 앞선다")
    void ranksByNetThenClawbackThenInsurer() {
        List<Result> ranked = CompareRankingCalculator.rank(List.of(
                candidate(1, "가보험", "34.8", null, rate(1, 1, "900")),
                candidate(2, "나보험", "24.0", null, rate(1, 1, "900")),
                candidate(3, "다보험", "10.0", null, rate(1, 1, "760")),
                candidate(4, "라보험", "24.0", null, rate(1, 1, "900"))), 100_000, AS_OF);

        // 2·3·4 는 순수령액 684,000 동률 → 환수가 가장 작은 3, 다음 환수 동률인 2·4 는 보험사명 순, 마지막 1
        assertThat(ranked).extracting(r -> r.candidate().offeringId()).containsExactly(3L, 2L, 4L, 1L);
    }

    @Test
    @DisplayName("정액 규칙은 기준 보험료와 무관하게 회차마다 정액을 더한다")
    void fixedAmountRule() {
        Rule fixed = new Rule(1, 3, null, new BigDecimal("1000.4"), "정액", "GA-CUR-2026-V1 v1");

        Result result = calc(candidate(1, "A", null, null, fixed), 50_000);

        assertThat(result.grossReceipt()).isEqualTo(3_000);
    }
}
