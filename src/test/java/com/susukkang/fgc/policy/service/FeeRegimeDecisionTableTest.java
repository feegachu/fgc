package com.susukkang.fgc.policy.service;

import com.susukkang.fgc.policy.code.FeeRegimeReasonCode;
import com.susukkang.fgc.policy.dto.FeeRegimeDecisionInput;
import com.susukkang.fgc.policy.service.FeeRegimeDecisionTable.Decision;
import com.susukkang.fgc.policy.service.FeeRegimeDecisionTable.Outcome;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 분급 체계 판정표(#536)를 DB 없이 검증하는 단위 테스트. 판정표는 위에서부터 처음 맞는 줄을 적용한다.
 */
class FeeRegimeDecisionTableTest {

    private static final long OFFERING_ID = 10L;
    private static final List<Long> ONLY_LINKED = List.of(OFFERING_ID);

    private static LocalDate d(String date) {
        return LocalDate.parse(date);
    }

    private static FeeRegimeDecisionInput input(String regime, String contract, String salesStart, String salesEnd,
                                                String documentDate) {
        return new FeeRegimeDecisionInput(1L, d(contract), OFFERING_ID, "V", d(salesStart),
                salesEnd == null ? null : d(salesEnd), "BD", d(documentDate), regime);
    }

    private static void assertReview(Decision decision, FeeRegimeReasonCode reason) {
        assertThat(decision.outcome()).isEqualTo(Outcome.REVIEW_REQUIRED);
        assertThat(decision.reasonCode()).isEqualTo(reason);
    }

    // 판정표 1: 정의된 세 값 밖의 체계 코드
    @Test
    void unknownRegimeNeedsReview() {
        assertReview(FeeRegimeDecisionTable.decide(
                input("TM_SPECIAL", "2027-03-02", "2027-01-01", null, "2026-12-01"), ONLY_LINKED),
                FeeRegimeReasonCode.UNKNOWN_REGIME);
        assertReview(FeeRegimeDecisionTable.decide(
                input(null, "2027-03-02", "2027-01-01", null, "2026-12-01"), ONLY_LINKED),
                FeeRegimeReasonCode.UNKNOWN_REGIME);
    }

    // 판정표 2: 계약일이 판매기간 밖 (시드 G2: 계약일 2026-11-10, 판매개시 2027-01-01)
    @Test
    void contractOutsideSalesPeriodNeedsReview() {
        assertReview(FeeRegimeDecisionTable.decide(
                input("FOUR_YEAR_2027", "2026-11-10", "2027-01-01", null, "2027-01-01"), ONLY_LINKED),
                FeeRegimeReasonCode.CONTRACT_OUTSIDE_SALES_PERIOD);
        assertReview(FeeRegimeDecisionTable.decide(
                input("CURRENT", "2027-01-01", "2026-01-01", "2026-12-31", "2026-01-01"), ONLY_LINKED),
                FeeRegimeReasonCode.CONTRACT_OUTSIDE_SALES_PERIOD);
        // 판매종료일 당일 계약은 판매기간 안이다
        assertThat(FeeRegimeDecisionTable.decide(
                input("CURRENT", "2026-12-31", "2026-01-01", "2026-12-31", "2026-01-01"), ONLY_LINKED).outcome())
                .isEqualTo(Outcome.NOT_APPLICABLE);
    }

    // 판정표 2-1: 계약일을 포함하는 판매버전이 연결된 판매버전 하나뿐이 아님
    @Test
    void offeringNotUniqueNeedsReview() {
        FeeRegimeDecisionInput input = input("CURRENT", "2027-03-02", "2026-01-01", null, "2026-01-01");
        assertReview(FeeRegimeDecisionTable.decide(input, List.of(OFFERING_ID, 11L)), FeeRegimeReasonCode.OFFERING_NOT_UNIQUE);
        assertReview(FeeRegimeDecisionTable.decide(input, List.of()), FeeRegimeReasonCode.OFFERING_NOT_UNIQUE);
        assertReview(FeeRegimeDecisionTable.decide(input, List.of(11L)), FeeRegimeReasonCode.OFFERING_NOT_UNIQUE);
        assertReview(FeeRegimeDecisionTable.decide(input, null), FeeRegimeReasonCode.OFFERING_NOT_UNIQUE);
    }

    // 판정표 3: 현행 + 적용례 미충족 → 계약일과 상관없이 분급 미적용 (시드 G1: 계약일 2027-03-02)
    @Test
    void currentOfferingBeforeEligibilityIsNotApplicableRegardlessOfContractDate() {
        for (String contract : List.of("2026-06-01", "2027-03-02", "2030-01-01")) {
            Decision decision = FeeRegimeDecisionTable.decide(
                    input("CURRENT", contract, "2026-01-01", null, "2026-01-01"), ONLY_LINKED);
            assertThat(decision.outcome()).isEqualTo(Outcome.NOT_APPLICABLE);
            assertThat(decision.reasonCode()).isEqualTo(FeeRegimeReasonCode.NOT_APPLICABLE);
        }
        // 판매개시일은 2027 이후여도 기초서류 작성/변경일이 시행일 전이면 미충족
        assertThat(FeeRegimeDecisionTable.decide(
                input("CURRENT", "2027-03-02", "2027-01-01", null, "2026-02-28"), ONLY_LINKED).outcome())
                .isEqualTo(Outcome.NOT_APPLICABLE);
    }

    // 판정표 4: 현행으로 선언됐으나 적용례 충족
    @Test
    void currentButEligibleNeedsReview() {
        assertReview(FeeRegimeDecisionTable.decide(
                input("CURRENT", "2027-03-02", "2027-01-01", null, "2026-12-01"), ONLY_LINKED),
                FeeRegimeReasonCode.CURRENT_BUT_ELIGIBLE);
    }

    // 판정표 5 + 시나리오 2: 계약일·판매개시일이 같고 기초서류 작성일만 다르면 결과가 다르다
    @Test
    void sameContractAndSalesStartDifferByDocumentDate() {
        assertThat(FeeRegimeDecisionTable.decide(
                input("FOUR_YEAR_2027", "2027-03-02", "2027-01-01", null, "2026-12-01"), ONLY_LINKED).outcome())
                .isEqualTo(Outcome.LOOKUP_RULESET);
        assertReview(FeeRegimeDecisionTable.decide(
                input("FOUR_YEAR_2027", "2027-03-02", "2027-01-01", null, "2026-02-01"), ONLY_LINKED),
                FeeRegimeReasonCode.REGIME_NOT_ELIGIBLE);
    }

    // 시나리오 7: 판매개시일 2026-12-31 vs 2027-01-01 경계
    @Test
    void salesStartBoundaryAt20270101() {
        assertReview(FeeRegimeDecisionTable.decide(
                input("FOUR_YEAR_2027", "2027-03-02", "2026-12-31", null, "2026-12-01"), ONLY_LINKED),
                FeeRegimeReasonCode.REGIME_NOT_ELIGIBLE);
        assertThat(FeeRegimeDecisionTable.decide(
                input("FOUR_YEAR_2027", "2027-03-02", "2027-01-01", null, "2026-12-01"), ONLY_LINKED).outcome())
                .isEqualTo(Outcome.LOOKUP_RULESET);
    }

    // 시나리오 8: 기초서류 작성/변경일 2026-02-28 vs 2026-03-01 경계 (시행일 당일 포함, SRC-066)
    @Test
    void documentDateBoundaryAtActEffectiveDate() {
        assertReview(FeeRegimeDecisionTable.decide(
                input("FOUR_YEAR_2027", "2027-03-02", "2027-01-01", null, "2026-02-28"), ONLY_LINKED),
                FeeRegimeReasonCode.REGIME_NOT_ELIGIBLE);
        assertThat(FeeRegimeDecisionTable.decide(
                input("FOUR_YEAR_2027", "2027-03-02", "2027-01-01", null, "2026-03-01"), ONLY_LINKED).outcome())
                .isEqualTo(Outcome.LOOKUP_RULESET);
    }

    // 판정표 6: 4년 선언인데 판매개시일이 특례 종료(2028-12-31) 이후
    @Test
    void fourYearSalesStartAfterSpecialPeriodNeedsReview() {
        assertReview(FeeRegimeDecisionTable.decide(
                input("FOUR_YEAR_2027", "2029-01-02", "2029-01-01", null, "2028-12-01"), ONLY_LINKED),
                FeeRegimeReasonCode.FOUR_YEAR_AFTER_SPECIAL_PERIOD);
        assertThat(FeeRegimeDecisionTable.decide(
                input("FOUR_YEAR_2027", "2028-12-31", "2028-12-31", null, "2028-12-01"), ONLY_LINKED).outcome())
                .isEqualTo(Outcome.LOOKUP_RULESET);
    }

    // 판정표 7: 4년 선언, 계약일 2029-01-01 이후 (특례 종료 기준일 미확정 — 보수 처리)
    @Test
    void fourYearContractOnOrAfter20290101NeedsReview() {
        assertReview(FeeRegimeDecisionTable.decide(
                input("FOUR_YEAR_2027", "2029-01-01", "2028-12-31", null, "2028-12-01"), ONLY_LINKED),
                FeeRegimeReasonCode.FOUR_YEAR_AFTER_SPECIAL_PERIOD);
        assertThat(FeeRegimeDecisionTable.decide(
                input("FOUR_YEAR_2027", "2028-12-31", "2028-12-31", null, "2028-12-01"), ONLY_LINKED).outcome())
                .isEqualTo(Outcome.LOOKUP_RULESET);
    }

    // 판정표 8: 7년 선언인데 판매개시일이 특례기간(2028-12-31까지) 안
    @Test
    void sevenYearInSpecialPeriodNeedsReview() {
        assertReview(FeeRegimeDecisionTable.decide(
                input("SEVEN_YEAR_2029", "2029-03-02", "2028-12-31", null, "2028-12-01"), ONLY_LINKED),
                FeeRegimeReasonCode.SEVEN_YEAR_IN_SPECIAL_PERIOD);
        assertThat(FeeRegimeDecisionTable.decide(
                input("SEVEN_YEAR_2029", "2029-03-02", "2029-01-01", null, "2028-12-01"), ONLY_LINKED).outcome())
                .isEqualTo(Outcome.LOOKUP_RULESET);
    }

    // 시나리오 3·4: 같은 입력은 같은 결과이고, 판매채널은 판정 입력에 없다(SRC-036)
    @Test
    void sameInputGivesSameDecision() {
        FeeRegimeDecisionInput input = input("FOUR_YEAR_2027", "2027-03-02", "2027-01-01", null, "2026-12-01");
        assertThat(FeeRegimeDecisionTable.decide(input, ONLY_LINKED))
                .isEqualTo(FeeRegimeDecisionTable.decide(input, ONLY_LINKED));
    }

    // 판정표는 위에서부터 처음 맞는 줄을 적용한다: 알 수 없는 체계는 판매기간 검사보다 먼저 걸린다
    @Test
    void earlierRowWinsOverLaterRows() {
        assertReview(FeeRegimeDecisionTable.decide(
                input("UNKNOWN", "2020-01-01", "2027-01-01", null, "2026-12-01"), List.of()),
                FeeRegimeReasonCode.UNKNOWN_REGIME);
        assertReview(FeeRegimeDecisionTable.decide(
                input("CURRENT", "2020-01-01", "2026-01-01", null, "2026-01-01"), List.of()),
                FeeRegimeReasonCode.CONTRACT_OUTSIDE_SALES_PERIOD);
    }
}
