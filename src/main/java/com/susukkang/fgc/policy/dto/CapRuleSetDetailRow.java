package com.susukkang.fgc.policy.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 룰셋과 항목을 함께 조회한 한 행. 항목이 없는 룰셋은 항목 필드가 null이다.
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
public record CapRuleSetDetailRow(
        Long capRuleSetId,
        String paymentStage,
        LocalDate contractDateFrom,
        LocalDate contractDateTo,
        Integer firstYearMonths,
        BigDecimal premiumMultiplier,
        BigDecimal complianceDeductionPct,
        String refundAdditionCondition,
        BigDecimal warningUsagePct,
        Long capRuleItemId,
        String itemCode,
        String itemName,
        String inclusionStatus,
        String exclusionType,
        Boolean evidenceRequiredYn,
        String attributionMethod,
        String decisionReason
) {
}
