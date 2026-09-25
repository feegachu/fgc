package com.susukkang.fgc.policy.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicyStatus;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.policy.dto.ResolvedCommissionPolicy;
import com.susukkang.fgc.policy.entity.PolicyVersion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.NativeQuery;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface PolicyVersionRepository extends JpaRepository<PolicyVersion, Long> {

    @Query("""
            SELECT pv
            FROM PolicyVersion pv
            WHERE (:type IS NULL OR pv.policyType = :type)
              AND (:status IS NULL OR pv.status = :status)
              AND (CAST(:asOf AS LocalDate) IS NULL
                   OR (pv.effectiveFrom <= :asOf
                       AND (pv.effectiveTo IS NULL OR pv.effectiveTo >= :asOf)))
            ORDER BY pv.policyCode, pv.versionNo
            """)
    List<PolicyVersion> selectPolicyVersions(
            @Param("type") PolicyType type,
            @Param("asOf") LocalDate asOf,
            @Param("status") PolicyStatus status
    );

    // 계약 도메인의 엔티티를 중복 정의하지 않고 기존 계약 조인 조건을 유지한다.
    @NativeQuery(value = """
            SELECT pv.policy_version_id,
                   pv.policy_type,
                   :#{#paymentStage.name()} AS payment_stage,
                   po.fee_regime_code AS schedule_regime
            FROM fgc.policy_version pv
            JOIN fgc.insurance_contract c ON c.contract_id = :contractId
            JOIN fgc.product_offering po ON po.product_offering_id = c.product_offering_id
            WHERE pv.policy_type = 'CURRENT_COMMISSION'
              AND pv.status = 'ACTIVE'
              AND pv.effective_from <= c.contract_date
              AND (pv.effective_to IS NULL OR pv.effective_to >= c.contract_date)
              AND (pv.fee_regime_code IS NULL OR pv.fee_regime_code = po.fee_regime_code)
              AND (pv.basic_document_version IS NULL OR pv.basic_document_version = po.basic_document_version)
              AND po.active_yn = TRUE
              AND po.sales_start_date <= c.contract_date
              AND (po.sales_end_date IS NULL OR po.sales_end_date >= c.contract_date)
              AND EXISTS (
                  SELECT 1
                  FROM fgc.commission_rule cr
                  WHERE cr.policy_version_id = pv.policy_version_id
                    AND cr.payment_stage = :#{#paymentStage.name()}
                    AND (cr.insurer_id IS NULL OR cr.insurer_id = c.insurer_id)
                    AND (cr.product_offering_id IS NULL OR cr.product_offering_id = c.product_offering_id)
                    AND (cr.organization_id IS NULL OR cr.organization_id = c.organization_id)
              )
            ORDER BY pv.effective_from DESC, pv.version_no DESC, pv.policy_version_id DESC
            """, sqlResultSetMapping = "ResolvedCommissionPolicyMapping")
    List<ResolvedCommissionPolicy> findApplicableCurrentCommissionPolicies(
            @Param("contractId") Long contractId,
            @Param("paymentStage") PaymentStage paymentStage
    );

    // 정책 응답에 필요한 작성자·승인자 로그인 ID를 한 번에 조회한다.
    @Query(value = """
            SELECT user_id AS "userId", login_id AS "loginId"
            FROM fgc.app_user
            WHERE user_id IN (:userIds)
            """, nativeQuery = true)
    List<UserLogin> findUserLogins(@Param("userIds") Collection<Long> userIds);

    interface UserLogin {
        Long getUserId();
        String getLoginId();
    }
}
