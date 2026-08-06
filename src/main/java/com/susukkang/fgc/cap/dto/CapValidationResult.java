package com.susukkang.fgc.cap.dto;

import java.math.BigDecimal;

/**
 * 설명 : 지급 후보의 1,200% 한도 판정 결과
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
public record CapValidationResult(
        BigDecimal limitAmount,
        BigDecimal candidateIncludedAmount,
        BigDecimal includedAmount,
        BigDecimal remainingAmount,
        BigDecimal usagePct,
        String resultStatus
) {
}
