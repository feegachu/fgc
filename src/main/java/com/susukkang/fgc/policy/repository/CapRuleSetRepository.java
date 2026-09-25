package com.susukkang.fgc.policy.repository;

import com.susukkang.fgc.policy.dto.CapRuleSetDetailRow;
import com.susukkang.fgc.policy.entity.CapRuleSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface CapRuleSetRepository extends JpaRepository<CapRuleSet, Long> {

    @Query("""
            SELECT new com.susukkang.fgc.policy.dto.CapRuleSetDetailRow(
                crs.capRuleSetId, crs.paymentStage, crs.contractDateFrom, crs.contractDateTo,
                crs.firstYearMonths, crs.premiumMultiplier, crs.complianceDeductionPct,
                crs.refundAdditionCondition, crs.warningUsagePct,
                cri.capRuleItemId, ci.itemCode, ci.itemName, cri.inclusionStatus,
                cri.exclusionType, cri.evidenceRequiredYn, cri.attributionMethod, cri.decisionReason)
            FROM CapRuleSet crs
            LEFT JOIN CapRuleItem cri ON cri.capRuleSetId = crs.capRuleSetId
            LEFT JOIN CommissionItem ci ON ci.commissionItemId = cri.commissionItemId
            WHERE crs.policyVersionId = :policyVersionId
            ORDER BY crs.paymentStage, crs.capRuleSetId, ci.itemCode
            """)
    List<CapRuleSetDetailRow> selectCapRuleSets(@Param("policyVersionId") Long policyVersionId);
}
