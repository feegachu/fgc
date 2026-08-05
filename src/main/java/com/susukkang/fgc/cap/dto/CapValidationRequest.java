package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.InclusionDecisionStatus;

import java.math.BigDecimal;

public record CapValidationRequest(
        BigDecimal basePremiumAmount,
        BigDecimal premiumMultiplier,
        BigDecimal warningUsagePct,
        BigDecimal existingIncludedAmount,
        BigDecimal candidateAmount,
        InclusionDecisionStatus inclusionDecisionStatus
) {
}
