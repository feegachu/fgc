package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.InclusionDecisionStatus;

import java.math.BigDecimal;

/**
 * 설명 : 지급 후보의 1,200% 한도 판정 요청
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
public record CapValidationRequest(
        BigDecimal limitAmount,
        // 주의 판정 기준액 L(월납 × 배수) — CapCalculationResult#annualizedLimitBase (#490)
        BigDecimal annualizedLimitBase,
        BigDecimal warningUsagePct,
        BigDecimal existingIncludedAmount,
        BigDecimal candidateAmount,
        InclusionDecisionStatus inclusionDecisionStatus
) {
}
