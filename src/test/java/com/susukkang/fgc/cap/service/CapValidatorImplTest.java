package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.cap.dto.CapValidationRequest;
import com.susukkang.fgc.cap.dto.CapValidationResult;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import org.junit.jupiter.api.Test;

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
        assertThat(result.resultStatus()).isEqualTo("NORMAL");
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
        assertThat(result.resultStatus()).isEqualTo("VIOLATION");
    }

    private CapValidationRequest request(
            String existing,
            String candidate,
            InclusionDecisionStatus status
    ) {
        return new CapValidationRequest(
                new BigDecimal("1200000"),
                new BigDecimal("90"),
                new BigDecimal(existing),
                new BigDecimal(candidate),
                status
        );
    }
}
