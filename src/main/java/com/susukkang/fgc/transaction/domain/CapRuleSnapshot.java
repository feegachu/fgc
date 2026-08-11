package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.InclusionDecisionStatus;

import java.math.BigDecimal;

/**
 * 설명 : 지급 후보에 적용되는 한도 규칙과 기존 확정 산입액 스냅샷
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
public record CapRuleSnapshot(
        Long capRuleSetId,
        Long capRuleItemId,
        InclusionDecisionStatus ruleInclusionStatus,
        String decisionReason,
        BigDecimal basePremiumAmount,
        BigDecimal premiumMultiplier,
        BigDecimal warningUsagePct,
        BigDecimal existingIncludedAmount,
        BigDecimal complianceEvidenceAmount
) {
}
