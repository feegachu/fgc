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
            new Dar012Planner.PolicyInputs(new BigDecimal("900.000000"), new BigDecimal("12.0000"));

    private Dar012Plan plan(long seed, int contracts) {
        return Dar012Planner.plan("TEST", seed, contracts, BASE, POLICY);
    }

    @Test
    void sameSeedProducesIdenticalPlanAndReports() {
        Dar012Plan a = plan(20261118L, 1000);
        Dar012Plan b = plan(20261118L, 1000);
        assertThat(a).isEqualTo(b);
        assertThat(Dar012ReportWriter.metadata(a, 0)).isEqualTo(Dar012ReportWriter.metadata(b, 0));
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
        Set<String> active = p.agents().stream().filter(a -> "ACTIVE".equals(a.status()))
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
        assertThat(scenarios).contains("RECON_AMOUNT_DIFF", "COMMON_PATTERN_SHORT_10PCT", "CAP_AT_LIMIT", "CAP_OVER_LIMIT",
                "STATUS_LAPSED", "STATUS_REVIVED", "STATUS_TERMINATED", "REP_AMOUNT_UNCONFIRMED");
        assertThat(p.payments()).noneMatch(x -> x.contractNo().equals(p.contracts().get(3).contractNo()));
    }

    @Test
    void expectedAmountRoundsHalfUpToWholeWon() {
        Dar012Planner.PolicyInputs half = new Dar012Planner.PolicyInputs(new BigDecimal("50"), new BigDecimal("12"));
        // 1001.00 x 50% = 500.5 -> 501
        assertThat(Dar012Planner.expectedAmount(new BigDecimal("1001.00"), half)).isEqualTo(new BigDecimal("501.00"));
        // 1000.80 x 50% = 500.4 -> 500
        assertThat(Dar012Planner.expectedAmount(new BigDecimal("1000.80"), half)).isEqualTo(new BigDecimal("500.00"));
        // 1000.90 x 50% = 500.45 -> 500 (원 단위로 한 번만 반올림한다)
        assertThat(Dar012Planner.expectedAmount(new BigDecimal("1000.90"), half)).isEqualTo(new BigDecimal("500.00"));
    }

    @Test
    void capScenarioPaymentsMatchLimitDerivedFromMonthlyEquivalent() {
        // 월환산 보험료가 천 원 단위라 정수 배수로는 반올림이 드러나지 않아 소수 배수도 함께 본다.
        for (BigDecimal multiplier : List.of(new BigDecimal("12.0000"), new BigDecimal("12.3456"))) {
            Dar012Planner.PolicyInputs policy = new Dar012Planner.PolicyInputs(POLICY.insurerBaseRatePct(), multiplier);
            Dar012Plan p = Dar012Planner.plan("TEST", 20261118L, 1000, BASE, policy);
            Map<String, BigDecimal> monthly = p.contracts().stream()
                    .collect(Collectors.toMap(Dar012Plan.ContractRow::contractNo, Dar012Plan.ContractRow::monthlyEquivalent));
            Map<String, BigDecimal> paid = p.payments().stream()
                    .collect(Collectors.toMap(PaymentRow::contractNo, PaymentRow::amount));
            Function<String, BigDecimal> limit = no ->
                    monthly.get(no).multiply(multiplier).setScale(0, RoundingMode.HALF_UP).setScale(2);

            List<AnswerRow> atLimit = p.answers().stream().filter(a -> "CAP_AT_LIMIT".equals(a.scenario())).toList();
            List<AnswerRow> overLimit = p.answers().stream().filter(a -> "CAP_OVER_LIMIT".equals(a.scenario())).toList();
            assertThat(atLimit).isNotEmpty();
            assertThat(overLimit).isNotEmpty();
            assertThat(atLimit).allSatisfy(a -> {
                assertThat(paid.get(a.contractNo())).isEqualTo(limit.apply(a.contractNo()));
                assertThat(a.expectedAmount()).isEqualTo(limit.apply(a.contractNo()));
            });
            assertThat(overLimit).allSatisfy(a -> {
                assertThat(paid.get(a.contractNo())).isEqualTo(limit.apply(a.contractNo()).add(new BigDecimal("1000.00")));
                assertThat(a.expectedAmount()).isEqualTo(limit.apply(a.contractNo()));
            });
        }
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
