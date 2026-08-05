package com.susukkang.fgc.cap.dto;

import java.math.BigDecimal;

public record CapValidationResult(
        BigDecimal limitAmount,
        BigDecimal candidateIncludedAmount,
        BigDecimal includedAmount,
        BigDecimal remainingAmount,
        BigDecimal usagePct,
        String resultStatus
) {
}
