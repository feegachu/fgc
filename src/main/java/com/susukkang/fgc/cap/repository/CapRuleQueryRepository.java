package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.CapRuleItemView;
import com.susukkang.fgc.cap.dto.CapRuleSetView;
import com.susukkang.fgc.policy.entity.CapRuleSet;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 한도 계산에 적용할 룰셋과 수수료 항목별 산입 규칙을 DTO로 조회한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-10-02
 */
@Transactional(readOnly = true)
public interface CapRuleQueryRepository extends Repository<CapRuleSet, Long> {

    /** 구체적인 범위, 적용 시작일, ID 순으로 우선하는 한 건을 조회하며 없으면 null을 반환한다. */
    default CapRuleSetView findApplicableRuleSet(
            String paymentStage,
            LocalDate contractDate,
            Long insurerId,
            String productGroupCode,
            String channelCode
    ) {
        return findApplicableRuleSets(paymentStage, contractDate, insurerId, productGroupCode, channelCode,
                PageRequest.of(0, 1)).stream().findFirst().orElse(null);
    }

    // NULL 범위는 전체 적용이다. Pageable로 DB 조회 건수를 제한하고 별도 count 쿼리는 실행하지 않는다.
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
                crs.warningUsagePct
            )
              FROM CapRuleSet crs, PolicyVersion pv
             WHERE pv.policyVersionId = crs.policyVersionId
               AND pv.status = com.susukkang.fgc.common.code.PolicyStatus.ACTIVE
               AND crs.paymentStage = :paymentStage
               AND crs.contractDateFrom <= :contractDate
               AND (crs.contractDateTo IS NULL OR crs.contractDateTo >= :contractDate)
               AND (crs.insurerId IS NULL OR crs.insurerId = :insurerId)
               AND (crs.productGroupCode IS NULL OR crs.productGroupCode = :productGroupCode)
               AND (crs.channelCode IS NULL OR crs.channelCode = :channelCode)
             ORDER BY
                   (CASE WHEN crs.insurerId IS NOT NULL THEN 1 ELSE 0 END
                  + CASE WHEN crs.productGroupCode IS NOT NULL THEN 1 ELSE 0 END
                  + CASE WHEN crs.channelCode IS NOT NULL THEN 1 ELSE 0 END) DESC,
                   crs.contractDateFrom DESC,
                   crs.capRuleSetId DESC
            """)
    List<CapRuleSetView> findApplicableRuleSets(
            @Param("paymentStage") String paymentStage,
            @Param("contractDate") LocalDate contractDate,
            @Param("insurerId") Long insurerId,
            @Param("productGroupCode") String productGroupCode,
            @Param("channelCode") String channelCode,
            Pageable pageable
    );

    /** 룰셋에 속한 항목별 분류를 조회하며 항목이 없으면 빈 목록을 반환한다. */
    @Query("""
            SELECT new com.susukkang.fgc.cap.dto.CapRuleItemView(
                cri.capRuleItemId,
                cri.commissionItemId,
                ci.itemCode,
                ci.itemName,
                cri.inclusionStatus,
                cri.exclusionType,
                cri.evidenceRequiredYn,
                cri.attributionMethod,
                cri.decisionReason
            )
              FROM CapRuleItem cri, CommissionItem ci
             WHERE ci.commissionItemId = cri.commissionItemId
               AND cri.capRuleSetId = :capRuleSetId
            """)
    List<CapRuleItemView> findRuleItems(@Param("capRuleSetId") Long capRuleSetId);
}
