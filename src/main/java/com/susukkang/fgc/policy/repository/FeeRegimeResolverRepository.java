package com.susukkang.fgc.policy.repository;

import com.susukkang.fgc.policy.entity.PolicyVersion;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 설명 : 분급 체계 판정(ProductFeeRegimeResolver)에 필요한 판매버전 후보와 룰셋 후보를 읽는 Repository.
 * 읽기 전용이며 후보 중 하나를 고르지 않는다(LIMIT 1 금지).
 */
public interface FeeRegimeResolverRepository extends Repository<PolicyVersion, Long> {

    // 같은 상품·판매채널에서 계약일을 판매기간에 포함하는 판매버전 ID (연결된 판매버전 포함)
    @Query(value = """
            SELECT other.product_offering_id
              FROM fgc.insurance_contract c
              JOIN fgc.product_offering linked ON linked.product_offering_id = c.product_offering_id
              JOIN fgc.product_offering other
                ON other.product_id = linked.product_id
               AND other.channel_code = linked.channel_code
               AND other.sales_start_date <= c.contract_date
               AND (other.sales_end_date IS NULL OR other.sales_end_date >= c.contract_date)
             WHERE c.contract_id = :contractId
             ORDER BY other.product_offering_id
            """, nativeQuery = true)
    List<Long> findOfferingIdsCoveringContractDate(@Param("contractId") Long contractId);

    // 정책 유형이 체계와 맞는 ACTIVE 정책버전 아래, 선택 키에 입력값이 들어가는 룰셋 후보.
    // 룰셋의 기초서류 버전이 NULL 이면 모든 버전에 맞는 것으로 본다(#534 선택 키).
    @Query(value = """
            SELECT r.fee_regime_ruleset_id AS "rulesetId",
                   r.policy_version_id AS "policyVersionId",
                   r.review_required_yn AS "reviewRequired",
                   r.basic_document_version AS "basicDocumentVersion"
              FROM fgc.fee_regime_ruleset r
              JOIN fgc.policy_version pv ON pv.policy_version_id = r.policy_version_id
             WHERE pv.policy_type = :policyType
               AND pv.status = 'ACTIVE'
               AND pv.effective_from <= :contractDate
               AND (pv.effective_to IS NULL OR pv.effective_to >= :contractDate)
               AND r.fee_regime_code = :feeRegimeCode
               AND (pv.basic_document_version IS NULL OR pv.basic_document_version = :basicDocumentVersion)
               AND (r.basic_document_version IS NULL OR r.basic_document_version = :basicDocumentVersion)
               AND r.sales_start_date_from <= :salesStartDate
               AND (r.sales_start_date_to IS NULL OR r.sales_start_date_to >= :salesStartDate)
               AND (r.basic_document_date_from IS NULL OR r.basic_document_date_from <= :basicDocumentDate)
               AND (r.basic_document_date_to IS NULL OR r.basic_document_date_to >= :basicDocumentDate)
             ORDER BY r.fee_regime_ruleset_id
            """, nativeQuery = true)
    List<RulesetCandidate> findRulesetCandidates(
            @Param("policyType") String policyType,
            @Param("feeRegimeCode") String feeRegimeCode,
            @Param("contractDate") LocalDate contractDate,
            @Param("salesStartDate") LocalDate salesStartDate,
            @Param("basicDocumentVersion") String basicDocumentVersion,
            @Param("basicDocumentDate") LocalDate basicDocumentDate
    );

    // #537: 선택된 룰셋 헤더. 승인된 정책 아래의 룰셋은 잠금 트리거로 바뀌지 않으므로 같은 ID로 다시 읽어도 같다.
    @Query(value = """
            SELECT r.fee_regime_ruleset_id AS "rulesetId",
                   r.policy_version_id AS "policyVersionId",
                   r.fee_regime_code AS "feeRegimeCode",
                   r.sales_start_date_from AS "salesStartDateFrom",
                   r.sales_start_date_to AS "salesStartDateTo",
                   r.basic_document_version AS "basicDocumentVersion",
                   r.basic_document_date_from AS "basicDocumentDateFrom",
                   r.basic_document_date_to AS "basicDocumentDateTo",
                   r.max_installments AS "maxInstallments",
                   r.long_term_maintenance_from AS "longTermMaintenanceFrom",
                   r.long_term_maintenance_to AS "longTermMaintenanceTo"
              FROM fgc.fee_regime_ruleset r
             WHERE r.fee_regime_ruleset_id = :rulesetId
            """, nativeQuery = true)
    Optional<RulesetHeader> findRulesetHeader(@Param("rulesetId") Long rulesetId);

    @Query(value = """
            SELECT l.limit_code AS "limitCode",
                   l.limit_pct AS "limitPct",
                   l.includes_indirect_expense_yn AS "includesIndirectExpense"
              FROM fgc.fee_regime_ruleset_limit l
             WHERE l.fee_regime_ruleset_id = :rulesetId
             ORDER BY l.limit_code
            """, nativeQuery = true)
    List<RulesetLimitRow> findRulesetLimits(@Param("rulesetId") Long rulesetId);

    interface RulesetCandidate {
        Long getRulesetId();
        Long getPolicyVersionId();
        Boolean getReviewRequired();
        String getBasicDocumentVersion();
    }

    interface RulesetHeader {
        Long getRulesetId();
        Long getPolicyVersionId();
        String getFeeRegimeCode();
        LocalDate getSalesStartDateFrom();
        LocalDate getSalesStartDateTo();
        String getBasicDocumentVersion();
        LocalDate getBasicDocumentDateFrom();
        LocalDate getBasicDocumentDateTo();
        Integer getMaxInstallments();
        Integer getLongTermMaintenanceFrom();
        Integer getLongTermMaintenanceTo();
    }

    interface RulesetLimitRow {
        String getLimitCode();
        BigDecimal getLimitPct();
        Boolean getIncludesIndirectExpense();
    }
}
