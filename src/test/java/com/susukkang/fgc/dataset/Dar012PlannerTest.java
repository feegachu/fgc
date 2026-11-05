package com.susukkang.fgc.dataset;

import com.susukkang.fgc.dataset.Dar012Plan.AnswerRow;
import com.susukkang.fgc.dataset.Dar012Plan.PaymentRow;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Dar012PlannerTest {
    private static final LocalDate BASE = LocalDate.of(2026, 9, 10);
    private static final Dar012Planner.PolicyInputs POLICY =
            new Dar012Planner.PolicyInputs(new BigDecimal("900.000000"), new BigDecimal("12.0000"),
                    java.util.Map.of("FGL01", 240, "FGL02", 240, "FGL03", 120, "FGL04", 120, "FGN01", 240),
                    new BigDecimal("650.000000"), List.of(new Dar012Planner.FirstYearLine(new BigDecimal("900.000000"), 1),
                            new Dar012Planner.FirstYearLine(new BigDecimal("8.000000"), 11)));

    private Dar012Plan plan(long seed, int contracts) {
        return Dar012Planner.plan("TEST", seed, contracts, BASE, POLICY);
    }

    @Test
    void sameSeedProducesIdenticalPlanAndReports() {
        Dar012Plan a = plan(20261118L, 1000);
        Dar012Plan b = plan(20261118L, 1000);
        assertThat(a).isEqualTo(b);
        assertThat(Dar012ReportWriter.metadata(a, "A-FC-001")).isEqualTo(Dar012ReportWriter.metadata(b, "A-FC-001"));
        assertThat(Dar012ReportWriter.answersCsv(a)).isEqualTo(Dar012ReportWriter.answersCsv(b));
    }

    @Test
    void differentSeedChangesData() {
        assertThat(plan(1L, 1000).contracts()).isNotEqualTo(plan(2L, 1000).contracts());
    }

    @Test
    void buildsSixHundredAgentsWithAtLeastFiveHundredActiveAcrossTwelveBranches() {
        Dar012Plan p = plan(20261118L, 300);
        assertThat(p.agents()).hasSize(600);
        assertThat(p.agents().stream().filter(a -> "ACTIVE".equals(a.status())).count()).isGreaterThanOrEqualTo(500);
        assertThat(p.agents().stream().map(Dar012Plan.AgentRow::status).collect(Collectors.toSet()))
                .containsExactlyInAnyOrder("ACTIVE", "INACTIVE", "TERMINATED");
        assertThat(p.organizations().stream().filter(o -> "BRANCH".equals(o.type()))).hasSize(12);
        assertThat(p.agents().stream().map(Dar012Plan.AgentRow::name).collect(Collectors.toSet()).size()).isLessThan(600);
        assertThat(p.agentCodes().stream().map(Dar012Plan.AgentCodeRow::insurerCode).collect(Collectors.toSet()))
                .hasSizeGreaterThanOrEqualTo(4);
    }

    @Test
    void contractsAreUniqueAndOnlyActiveAgentsSign() {
        Dar012Plan p = plan(20261118L, 3000);
        Set<String> numbers = new HashSet<>();
        p.contracts().forEach(c -> assertThat(numbers.add(c.contractNo())).isTrue());
        Set<String> active = p.agents().stream().filter(a -> "ACTIVE".equals(a.status()) && "FC".equals(a.rankCode()))
                .map(Dar012Plan.AgentRow::code).collect(Collectors.toSet());
        assertThat(p.contracts()).allSatisfy(c -> {
            assertThat(active).contains(c.agentCode());
            assertThat(c.contractNo()).matches("FGC-FG[LN]0\\d-2026(07|08)-[5-9]\\d{3}");
            assertThat(c.monthlyEquivalent().signum()).isPositive();
        });
    }

    @Test
    void everyPaymentIsRoundedPerRowAndPlantedAnomaliesAreInTheAnswerKey() {
        Dar012Plan p = plan(20261118L, 1000);
        assertThat(p.payments()).allSatisfy(x ->
                assertThat(x.amount()).isEqualByComparingTo(x.amount().setScale(0, java.math.RoundingMode.HALF_UP)));
        Set<String> scenarios = p.answers().stream().map(AnswerRow::scenario).collect(Collectors.toSet());
        assertThat(scenarios).contains("RECON_AMOUNT_DIFF", "COMMON_PATTERN_SHORT_10PCT", "RECON_PAID_AT_CAP_MULTIPLE", "RECON_PAID_OVER_CAP_MULTIPLE",
                "STATUS_LAPSED", "STATUS_REVIVED", "STATUS_TERMINATED", "REP_AMOUNT_UNCONFIRMED");
        assertThat(p.payments()).noneMatch(x -> x.contractNo().equals(p.contracts().get(3).contractNo()));
    }

    @Test
    void expectedAmountRoundsHalfUpToWholeWon() {
        Dar012Planner.PolicyInputs half = new Dar012Planner.PolicyInputs(new BigDecimal("50"), new BigDecimal("12"), POLICY.paymentTermMonthsByInsurer(),
                POLICY.gaFcBaseRatePct(), POLICY.insurerFirstYearLines());
        // 1001.00 x 50% = 500.5 -> 501
        assertThat(Dar012Planner.expectedAmount(new BigDecimal("1001.00"), half)).isEqualTo(new BigDecimal("501.00"));
        // 1000.80 x 50% = 500.4 -> 500
        assertThat(Dar012Planner.expectedAmount(new BigDecimal("1000.80"), half)).isEqualTo(new BigDecimal("500.00"));
        // 1000.90 x 50% = 500.45 -> 500 (원 단위로 한 번만 반올림한다)
        assertThat(Dar012Planner.expectedAmount(new BigDecimal("1000.90"), half)).isEqualTo(new BigDecimal("500.00"));
    }

    @Test
    void paymentsAtTheCapMultipleAreReconciliationDifferencesNotCapBoundaryAnswers() {
        // 월환산 보험료가 천 원 단위라 정수 배수로는 반올림이 드러나지 않아 소수 배수도 함께 본다.
        for (BigDecimal multiplier : List.of(new BigDecimal("12.0000"), new BigDecimal("12.3456"))) {
            Dar012Planner.PolicyInputs policy = new Dar012Planner.PolicyInputs(POLICY.insurerBaseRatePct(), multiplier, POLICY.paymentTermMonthsByInsurer(),
                    POLICY.gaFcBaseRatePct(), POLICY.insurerFirstYearLines());
            Dar012Plan p = Dar012Planner.plan("TEST", 20261118L, 1000, BASE, policy);
            Map<String, BigDecimal> monthly = p.contracts().stream()
                    .collect(Collectors.toMap(Dar012Plan.ContractRow::contractNo, Dar012Plan.ContractRow::monthlyEquivalent));
            Map<String, BigDecimal> paid = p.payments().stream()
                    .collect(Collectors.toMap(PaymentRow::contractNo, PaymentRow::amount));
            Function<String, BigDecimal> limit = no ->
                    monthly.get(no).multiply(multiplier).setScale(0, RoundingMode.HALF_UP).setScale(2);

            List<AnswerRow> atMultiple = p.answers().stream().filter(a -> "RECON_PAID_AT_CAP_MULTIPLE".equals(a.scenario())).toList();
            List<AnswerRow> overMultiple = p.answers().stream().filter(a -> "RECON_PAID_OVER_CAP_MULTIPLE".equals(a.scenario())).toList();
            assertThat(atMultiple).isNotEmpty();
            assertThat(overMultiple).isNotEmpty();
            assertThat(atMultiple).allSatisfy(a -> {
                assertThat(paid.get(a.contractNo())).isEqualTo(limit.apply(a.contractNo()));
                assertThat(a.expectedAmount()).isEqualTo(Dar012Planner.expectedAmount(monthly.get(a.contractNo()), policy));
            });
            assertThat(overMultiple).allSatisfy(a -> {
                assertThat(paid.get(a.contractNo())).isEqualTo(limit.apply(a.contractNo()).add(new BigDecimal("1000.00")));
                assertThat(a.expectedAmount()).isEqualTo(Dar012Planner.expectedAmount(monthly.get(a.contractNo()), policy));
            });
            assertThat(atMultiple).extracting(AnswerRow::expectedResultType).containsOnly("RECON_AMOUNT_MISMATCH");
            assertThat(overMultiple).extracting(AnswerRow::expectedResultType).containsOnly("RECON_AMOUNT_MISMATCH");
        }
    }

    @Test
    void capBoundaryRefundRatesPutTheLimitExactlyAtAndOneWonBelowFirstYearExpected() {
        Dar012Plan p = plan(20261118L, 300);
        assertThat(p.capBoundaries()).hasSize(Dar012Planner.CAP_BOUNDARY_CONTRACTS);
        Map<String, Dar012Plan.ContractRow> contracts = p.contracts().stream()
                .collect(Collectors.toMap(Dar012Plan.ContractRow::contractNo, Function.identity()));
        Map<String, AnswerRow> capAnswers = p.answers().stream().filter(a -> a.expectedResultType().startsWith("CAP_"))
                .collect(Collectors.toMap(AnswerRow::contractNo, Function.identity()));
        assertThat(capAnswers).hasSize(Dar012Planner.CAP_BOUNDARY_CONTRACTS);
        long[] overByWon = {0, 1};
        for (int n = 0; n < p.capBoundaries().size(); n++) {
            Dar012Plan.CapBoundaryRow boundary = p.capBoundaries().get(n);
            Dar012Plan.ContractRow contract = contracts.get(boundary.contractNo());
            assertThat(contract.paymentTermMonths()).isEqualTo(boundary.paymentTermMonths());
            assertThat(contract.contractDate().getMonthValue()).isEqualTo(7);
            BigDecimal limitBase = contract.monthlyEquivalent().multiply(POLICY.capPremiumMultiplier()).setScale(0, RoundingMode.HALF_UP);
            BigDecimal refund = limitBase.multiply(boundary.month12RefundRatePct()).movePointLeft(2).setScale(0, RoundingMode.HALF_UP);
            BigDecimal firstYear = Dar012Planner.firstYearExpected(contract.monthlyEquivalent(), POLICY);
            assertThat(firstYear.subtract(limitBase.subtract(refund))).isEqualByComparingTo(BigDecimal.valueOf(overByWon[n]));
            assertThat(capAnswers.get(boundary.contractNo()).expectedAmount()).isEqualByComparingTo(limitBase.subtract(refund));
        }
    }

    @Test
    void representativeContractsExceptTheUnconfirmedOneHaveAGaPaymentAtTheFcBaseRate() {
        Dar012Plan p = plan(20261118L, 50);
        var rep = p.contracts().subList(0, Dar012Planner.REPRESENTATIVE_CONTRACTS);
        assertThat(p.gaPayments()).extracting(Dar012Plan.GaPaymentRow::contractNo)
                .containsExactly(rep.get(0).contractNo(), rep.get(1).contractNo(), rep.get(2).contractNo(), rep.get(4).contractNo());
        assertThat(p.gaPayments()).allSatisfy(g -> {
            Dar012Plan.ContractRow contract = rep.stream().filter(c -> c.contractNo().equals(g.contractNo())).findFirst().orElseThrow();
            assertThat(g.agentCode()).isEqualTo("D12-A-0001");
            assertThat(g.amount()).isEqualByComparingTo(contract.monthlyEquivalent().multiply(new BigDecimal("6.5")));
            assertThat(g.paidOn()).isEqualTo(contract.contractDate());
        });
    }

    @Test
    void representativeAgentHasNormalLapsedRevivedUnconfirmedAndClawbackCases() {
        Dar012Plan p = plan(20261118L, 50);
        var rep = p.contracts().subList(0, 5);
        assertThat(rep).allSatisfy(c -> assertThat(c.agentCode()).isEqualTo("D12-A-0001"));
        assertThat(rep.stream().map(Dar012Plan.ContractRow::currentStatus).toList())
                .containsExactly("ACTIVE", "LAPSED", "REVIVED", "ACTIVE", "TERMINATED");
    }

    @Test
    void rejectsContractCountThatOverflowsSerialRange() {
        assertThatThrownBy(() -> plan(1L, 200_000)).isInstanceOf(IllegalArgumentException.class);
    }
}
