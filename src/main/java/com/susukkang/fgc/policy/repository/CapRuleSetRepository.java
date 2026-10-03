package com.susukkang.fgc.policy.repository;

import com.susukkang.fgc.cap.dto.CapRuleSetView;
import com.susukkang.fgc.policy.dto.CapRuleSetDetailRow;
import com.susukkang.fgc.policy.entity.CapRuleSet;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 정책 버전별 한도 룰셋과 수수료 항목별 판정을 조회하는 Repository
 *
 * @author hjKang
 * @version 1.1
 * @since 2026-09-26
 */
public interface CapRuleSetRepository extends JpaRepository<CapRuleSet, Long> {

    @Query("""
            SELECT new com.susukkang.fgc.policy.dto.CapRuleSetDetailRow(
                crs.capRuleSetId,
                crs.paymentStage,
                crs.contractDateFrom,
                crs.contractDateTo,
                crs.firstYearMonths,
                crs.premiumMultiplier,
                crs.complianceDeductionPct,
                crs.refundAdditionCondition,
                crs.warningUsagePct,
                cri.capRuleItemId,
                ci.itemCode,
                ci.itemName,
                cri.inclusionStatus,
                cri.exclusionType,
                cri.evidenceRequiredYn,
                cri.attributionMethod,
                cri.decisionReason)
            FROM CapRuleSet crs
            LEFT JOIN CapRuleItem cri ON cri.capRuleSetId = crs.capRuleSetId
            LEFT JOIN CommissionItem ci ON ci.commissionItemId = cri.commissionItemId
            WHERE crs.policyVersionId = :policyVersionId
            ORDER BY crs.paymentStage, crs.capRuleSetId, ci.itemCode
            """)
    List<CapRuleSetDetailRow> selectCapRuleSets(@Param("policyVersionId") Long policyVersionId);

    // #379 validation_run 생성 시 policy_snapshot 조립용. CapRuleMapper#findApplicableRuleSet
    // (계약 1건 기준 가장 구체적인 1건)과 달리 payment_stage 등으로 좁히지 않고 asOfDate
    // 시점에 유효한 전체를 나열한다.
    @Query("""
            SELECT new com.susukkang.fgc.cap.dto.CapRuleSetView(
                crs.capRuleSetId,
                crs.policyVersionId,
                crs.paymentStage,
                crs.contractDateFrom,
                crs.contractDateTo,
                crs.insurerId,
                crs.productGroupCode,
                crs.channelCode,
                crs.firstYearMonths,
                crs.premiumMultiplier,
                crs.complianceDeductionPct,
                crs.refundAdditionCondition,
                crs.warningUsagePct)
            FROM CapRuleSet crs
            JOIN PolicyVersion pv ON pv.policyVersionId = crs.policyVersionId
            WHERE pv.status = com.susukkang.fgc.common.code.PolicyStatus.ACTIVE
              AND crs.contractDateFrom <= :asOfDate
              AND (crs.contractDateTo IS NULL OR crs.contractDateTo >= :asOfDate)
            ORDER BY crs.paymentStage, crs.capRuleSetId
            """)
    List<CapRuleSetView> findActiveCapRuleSets(@Param("asOfDate") LocalDate asOfDate);
}
