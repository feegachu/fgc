package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapValidationRequest;
import com.susukkang.fgc.cap.dto.CapValidationResult;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : CapValidator 단위 테스트
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
class CapValidatorImplTest {

    private final CapValidator validator = new CapValidatorImpl();

    @Test
    void returnsNormalWhenCandidateStaysBelowWarningThreshold() {
        CapValidationResult result = validator.validate(request(
                "500000",
                "300000",
                InclusionDecisionStatus.INCLUDED
        ));

        assertThat(result.limitAmount()).isEqualByComparingTo("1200000");
        assertThat(result.includedAmount()).isEqualByComparingTo("800000");
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.NORMAL);
    }

    @Test
    void excludesCandidateFromCapWhenRuleClassifiesItAsExcluded() {
        CapValidationResult result = validator.validate(request(
                "500000",
                "300000",
                InclusionDecisionStatus.EXCLUDED
        ));

        assertThat(result.candidateIncludedAmount()).isZero();
        assertThat(result.includedAmount()).isEqualByComparingTo("500000");
    }

    @Test
    void returnsViolationWhenCandidateExceedsLimit() {
        CapValidationResult result = validator.validate(request(
                "1000000",
                "300000",
                InclusionDecisionStatus.INCLUDED
        ));

        assertThat(result.remainingAmount()).isNegative();
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.VIOLATION);
    }

    @Test
    void returnsReviewRequiredForReviewRequiredAttribution() {
        CapValidationResult result = validator.validate(request(
                "500000",
                "300000",
                InclusionDecisionStatus.REVIEW_REQUIRED
        ));

        assertThat(result.candidateIncludedAmount()).isZero();
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.REVIEW_REQUIRED);
    }

    // #490: 지급확정 판정도 CapCalculatorImplTest와 같은 경계(1,200% 예산의 90%)를 낸다.
    // 월납 100,000원(L = 1,200,000), 한도 = L − 해약환급금 + 준법경영비
    @ParameterizedTest(name = "한도 {0}, 산입 {1} → {2}")
    @CsvSource({
            "912000, 791999, NORMAL",
            "912000, 792000, WARNING",
            "912000, 912000, WARNING",
            "912000, 912001, VIOLATION",
            "1200000, 1079999, NORMAL",
            "1200000, 1080000, WARNING",
            "1203000, 1082999, NORMAL",
            "1203000, 1083000, WARNING"
    })
    void judgesWarningByRegulatoryBudgetLikeCalculator(String limit, String included, CapResultStatus expected) {
        CapValidationResult result = validator.validate(new CapValidationRequest(
                new BigDecimal(limit),
                new BigDecimal("1200000"),
                new BigDecimal("90"),
                BigDecimal.ZERO,
                new BigDecimal(included),
                InclusionDecisionStatus.INCLUDED
        ));

        assertThat(result.resultStatus()).isEqualTo(expected);
    }

    @Test
    void grossPaymentTotalCannotBeReducedByASeparateClawback() {
        var result = validator.validate(request("900000", "600000", InclusionDecisionStatus.INCLUDED));
        assertThat(result.includedAmount()).isEqualByComparingTo("1500000");
        assertThat(result.remainingAmount()).isEqualByComparingTo("-300000");
        assertThat(result.resultStatus()).isEqualTo(CapResultStatus.VIOLATION);
    }

    private CapValidationRequest request(
            String existing,
            String candidate,
            InclusionDecisionStatus status
    ) {
        return new CapValidationRequest(
                new BigDecimal("1200000"),
                new BigDecimal("1200000"),
                new BigDecimal("90"),
                new BigDecimal(existing),
                new BigDecimal(candidate),
                status
        );
    }
}
