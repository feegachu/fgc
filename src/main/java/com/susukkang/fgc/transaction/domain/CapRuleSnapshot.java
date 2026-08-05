package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.InclusionDecisionStatus;

import java.math.BigDecimal;

public record CapRuleSnapshot(
        Long capRuleSetId,
        Long capRuleItemId,
        InclusionDecisionStatus ruleInclusionStatus,
        String decisionReason,
        BigDecimal basePremiumAmount,
        BigDecimal premiumMultiplier,
        BigDecimal warningUsagePct,
        BigDecimal existingIncludedAmount
) {
}
