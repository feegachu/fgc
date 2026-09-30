package com.susukkang.fgc.cap.repository;

import com.susukkang.fgc.cap.dto.*;
import com.susukkang.fgc.common.code.PaymentStage;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 설명 : 한도 결과 화면의 월별 최신 판정·집계를 보존하는 JPA 네이티브 조회 저장소.
 * PostgreSQL ROW_NUMBER와 집계를 유지하며 지급 후보 필터는 순위 계산 전에,
 * 상태·지급단계 필터는 최신 판정 선정 후에 적용한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-30
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CapCheckQueryRepository {
    private final EntityManager entityManager;

    private static final String FIND_LATEST_BY_CONTRACT_AND_STAGE = """
            SELECT cc.cap_check_id                 AS "capCheckId",
                   cc.contract_id                   AS "contractId",
                   cc.payment_stage                 AS "paymentStage",
                   cc.check_kind                    AS "checkKind",
                   cc.as_of_date                    AS "asOfDate",
                   cc.cap_rule_set_id               AS "capRuleSetId",
                   cc.refund_rate_table_id          AS "refundRateTableId",
                   cc.base_premium_amount           AS "basePremiumAmount",
                   cc.refund_12m_amount             AS "refund12mAmount",
                   cc.compliance_deduction_amount   AS "complianceDeductionAmount",
                   cc.limit_amount                  AS "limitAmount",
                   cc.included_amount               AS "includedAmount",
                   cc.remaining_amount              AS "remainingAmount",
                   cc.usage_pct                     AS "usagePct",
                   cc.result_status                 AS "resultStatus",
                   cc.calculation_snapshot::text    AS "calculationSnapshotJson"
              FROM fgc.cap_check cc
             WHERE cc.contract_id = :contractId
               AND cc.payment_stage = :paymentStage

               AND (
                    cc.candidate_transaction_id IS NULL
                    OR EXISTS (
                        SELECT 1
                          FROM fgc.commission_transaction candidate_ct
                         WHERE candidate_ct.commission_transaction_id = cc.candidate_transaction_id
                           AND candidate_ct.status = 'CONFIRMED'
                    )
               )
             ORDER BY cc.checked_at DESC, cc.cap_check_id DESC
             LIMIT 1
            """;

    private static final String FIND_BY_ID = """
            SELECT cc.cap_check_id                 AS "capCheckId",
                   cc.contract_id                   AS "contractId",
                   ic.contract_no                   AS "contractNo",
                   cc.payment_stage                 AS "paymentStage",
                   cc.check_kind                    AS "checkKind",
                   cc.as_of_date                    AS "asOfDate",
                   cc.cap_rule_set_id               AS "capRuleSetId",
                   cc.refund_rate_table_id          AS "refundRateTableId",
                   cc.base_premium_amount           AS "basePremiumAmount",
                   cc.refund_12m_amount             AS "refund12mAmount",
                   cc.compliance_deduction_amount   AS "complianceDeductionAmount",
                   cc.limit_amount                  AS "limitAmount",
                   cc.included_amount               AS "includedAmount",
                   cc.remaining_amount              AS "remainingAmount",
                   cc.usage_pct                     AS "usagePct",
                   cc.result_status                 AS "resultStatus",
                   cc.calculation_snapshot::text    AS "calculationSnapshotJson"
              FROM fgc.cap_check cc
              JOIN fgc.insurance_contract ic ON ic.contract_id = cc.contract_id
             WHERE cc.cap_check_id = :capCheckId
            """;

    private static final String LATEST_SCOPED_CAP_CHECKS = """
            WITH ranked_cap_check AS (
                        SELECT cc.cap_check_id,
                               cc.contract_id,
                               ic.contract_no,
                               ic.agent_id,
                               ic.organization_id,
                               cc.payment_stage,
                               cc.as_of_date,
                               cc.base_premium_amount,
                               cc.refund_12m_amount,
                               cc.compliance_deduction_amount,
                               cc.limit_amount,
                               cc.included_amount,
                               cc.remaining_amount,
                               cc.usage_pct,
                               cc.result_status,
                               cc.cap_rule_set_id,
                               ROW_NUMBER() OVER (
                                   PARTITION BY cc.contract_id, cc.payment_stage,
                                                date_trunc('month', cc.as_of_date)
                                   ORDER BY cc.checked_at DESC, cc.cap_check_id DESC
                               ) AS latest_rank
                          FROM fgc.cap_check cc
                          JOIN fgc.insurance_contract ic ON ic.contract_id = cc.contract_id
                        WHERE (
                                cc.candidate_transaction_id IS NULL
                                OR EXISTS (
                                    SELECT 1
                                      FROM fgc.commission_transaction candidate_ct
                                     WHERE candidate_ct.commission_transaction_id = cc.candidate_transaction_id
                                       AND candidate_ct.status = 'CONFIRMED'
                                )
                            )
            __scopeFilters__
                    ), latest_cap_check AS (
                        SELECT *
                          FROM ranked_cap_check
                         WHERE latest_rank = 1
                    )
            """;

    private static final String SEARCH = """
            __latestScopedCapChecks__
            SELECT cap_check_id                 AS "capCheckId",
                   contract_id                  AS "contractId",
                   contract_no                  AS "contractNo",
                   payment_stage                AS "paymentStage",
                   as_of_date                   AS "asOfDate",
                   base_premium_amount          AS "basePremiumAmount",
                   refund_12m_amount            AS "refund12mAmount",
                   compliance_deduction_amount  AS "complianceDeductionAmount",
                   limit_amount                 AS "limitAmount",
                   included_amount              AS "includedAmount",
                   remaining_amount             AS "remainingAmount",
                   usage_pct                    AS "usagePct",
                   result_status                AS "resultStatus",
                   cap_rule_set_id              AS "capRuleSetId"
              FROM latest_cap_check
            __resultFilterWhere__
             ORDER BY as_of_date DESC, cap_check_id DESC
             LIMIT :limit OFFSET :offset
            """;

    private static final String COUNT = """
            __latestScopedCapChecks__
            SELECT COUNT(*)
              FROM latest_cap_check
            __resultFilterWhere__
            """;

    private static final String SUMMARIZE = """
            __latestScopedCapChecks__
            SELECT result_status AS "resultStatus", COUNT(*) AS "count"
              FROM latest_cap_check
            __stageFilter__
             GROUP BY result_status
            """;

    private static final String SUMMARIZE_BY_STAGE = """
            __latestScopedCapChecks__
            SELECT payment_stage AS "paymentStage",
                   COUNT(*) AS "contractCount",
                   COALESCE(SUM(ROUND(limit_amount, 0)), 0) AS "limitAmountTotal",
                   COALESCE(SUM(ROUND(included_amount, 0)), 0) AS "includedAmountTotal",
                   COALESCE(SUM(
                       CASE WHEN payment_stage = 'INSURER_TO_GA'
                            THEN ROUND(compliance_deduction_amount, 0)
                            ELSE 0
                       END
                   ), 0) AS "complianceDeductionAmountTotal",
                   CASE WHEN COALESCE(SUM(ROUND(limit_amount, 0)), 0) = 0
                        THEN 0.000000::numeric(12,6)
                        ELSE ROUND(
                            SUM(ROUND(included_amount, 0)) * 100 /
                            SUM(ROUND(limit_amount, 0)), 6)
                   END AS "usagePct",
                   COUNT(*) FILTER (WHERE result_status = 'VIOLATION') AS "violationCount",
                   COUNT(*) FILTER (WHERE result_status = 'WARNING') AS "warningCount",
                   COUNT(*) FILTER (WHERE result_status = 'REVIEW_REQUIRED') AS "reviewRequiredCount",
                   (ARRAY_AGG(contract_no ORDER BY
                        CASE result_status
                            WHEN 'VIOLATION' THEN 4
                            WHEN 'WARNING' THEN 3
                            WHEN 'REVIEW_REQUIRED' THEN 2
                            ELSE 1
                        END DESC,
                        usage_pct DESC NULLS LAST,
                        cap_check_id DESC))[1] AS "worstContractNo",
                   (ARRAY_AGG(usage_pct ORDER BY
                        CASE result_status
                            WHEN 'VIOLATION' THEN 4
                            WHEN 'WARNING' THEN 3
                            WHEN 'REVIEW_REQUIRED' THEN 2
                            ELSE 1
                        END DESC,
                        usage_pct DESC NULLS LAST,
                        cap_check_id DESC))[1] AS "worstUsagePct"
              FROM latest_cap_check
             GROUP BY payment_stage
             ORDER BY CASE payment_stage WHEN 'INSURER_TO_GA' THEN 1 ELSE 2 END
            """;

    private static final String SUMMARIZE_BY_AGENT = """
            __latestScopedCapChecks__
            SELECT a.agent_id AS "agentId",
                   a.agent_code AS "agentCode",
                   a.agent_name AS "agentName",
                   o.organization_id AS "organizationId",
                   o.organization_code AS "organizationCode",
                   o.organization_name AS "organizationName",
                   COUNT(*) AS "contractCount",
                   COALESCE(SUM(ROUND(lcc.limit_amount, 0)), 0) AS "limitAmountTotal",
                   COALESCE(SUM(ROUND(lcc.included_amount, 0)), 0) AS "includedAmountTotal",
                   CASE WHEN COALESCE(SUM(ROUND(lcc.limit_amount, 0)), 0) = 0
                        THEN 0.000000::numeric(12,6)
                        ELSE ROUND(
                            SUM(ROUND(lcc.included_amount, 0)) * 100 /
                            SUM(ROUND(lcc.limit_amount, 0)), 6)
                   END AS "usagePct",
                   COUNT(*) FILTER (WHERE lcc.result_status = 'VIOLATION') AS "violationCount",
                   COUNT(*) FILTER (WHERE lcc.result_status = 'WARNING') AS "warningCount",
                   COUNT(*) FILTER (WHERE lcc.result_status = 'REVIEW_REQUIRED') AS "reviewRequiredCount",
                   (ARRAY_AGG(lcc.contract_no ORDER BY
                        CASE lcc.result_status
                            WHEN 'VIOLATION' THEN 4
                            WHEN 'WARNING' THEN 3
                            WHEN 'REVIEW_REQUIRED' THEN 2
                            ELSE 1
                        END DESC,
                        lcc.usage_pct DESC NULLS LAST,
                        lcc.cap_check_id DESC))[1] AS "worstContractNo",
                   (ARRAY_AGG(lcc.usage_pct ORDER BY
                        CASE lcc.result_status
                            WHEN 'VIOLATION' THEN 4
                            WHEN 'WARNING' THEN 3
                            WHEN 'REVIEW_REQUIRED' THEN 2
                            ELSE 1
                        END DESC,
                        lcc.usage_pct DESC NULLS LAST,
                        lcc.cap_check_id DESC))[1] AS "worstUsagePct"
              FROM latest_cap_check lcc
              JOIN fgc.agent a ON a.agent_id = lcc.agent_id
              JOIN fgc.organization o ON o.organization_id = lcc.organization_id
             WHERE lcc.payment_stage = 'GA_TO_FC'
             GROUP BY a.agent_id, a.agent_code, a.agent_name,
                      o.organization_id, o.organization_code, o.organization_name
             ORDER BY SUM(ROUND(lcc.included_amount, 0)) DESC, a.agent_code
            """;

    private static final String FIND_DETAILS_BY_CAP_CHECK_ID = """
            SELECT ccd.detail_seq             AS "detailSeq",
                   ccd.commission_item_id      AS "commissionItemId",
                   ccd.item_code                AS "itemCode",
                   ccd.item_name                AS "itemName",
                   ccd.schedule_line_id        AS "scheduleLineId",
                   ccd.contract_month_no        AS "contractMonthNo",
                   ccd.classification_snapshot AS classification,
                   ccd.amount                  AS amount,
                   ccd.decision_reason         AS "decisionReason",
                   ccd.evidence_ref            AS "evidenceRef"
              FROM fgc.cap_check_detail ccd
             WHERE ccd.cap_check_id = :capCheckId
             ORDER BY ccd.detail_seq
            """;

    private static final String SELECT_COMPLIANCE_EVIDENCE_AMOUNT = """
            SELECT SUM(
               CASE
                   WHEN ct.cashflow_type = 'DEDUCTION'
                       THEN -ROUND(ta.attributed_amount, 0)
                   ELSE ROUND(ta.attributed_amount, 0)
               END
            )
            FROM fgc.transaction_attribution ta
                     JOIN fgc.commission_transaction ct
                          ON ct.commission_transaction_id = ta.commission_transaction_id
                     JOIN fgc.insurance_contract c
                          ON c.contract_id = ta.contract_id
            WHERE ta.contract_id = :contractId
              AND ta.exclusion_type_snapshot = 'COMPLIANCE_3PCT'
              AND NULLIF(BTRIM(ta.evidence_ref), '') IS NOT NULL
              AND ct.payment_stage = :paymentStage
              AND ct.status = 'CONFIRMED'
              AND ta.attribution_date >= c.contract_date
              AND ta.attribution_date < c.contract_date + INTERVAL '12 months'
            """;

    private static final String EXISTS_APPLICABLE_RULE_SET = """
            SELECT EXISTS (
                SELECT 1
                  FROM fgc.insurance_contract c
                  JOIN fgc.product_offering po
                    ON po.product_offering_id = c.product_offering_id
                  JOIN fgc.product p
                    ON p.product_id = po.product_id
                  JOIN fgc.cap_rule_set crs
                    ON crs.payment_stage = :paymentStage
                   AND crs.contract_date_from <= c.contract_date
                   AND (crs.contract_date_to IS NULL OR crs.contract_date_to >= c.contract_date)
                   AND (crs.insurer_id IS NULL OR crs.insurer_id = c.insurer_id)
                   AND (crs.product_group_code IS NULL OR crs.product_group_code = p.product_group_code)
                   AND (crs.channel_code IS NULL OR crs.channel_code = po.channel_code)
                  JOIN fgc.policy_version pv
                    ON pv.policy_version_id = crs.policy_version_id
                   AND pv.status = 'ACTIVE'
                 WHERE c.contract_id = :contractId
            )
            """;

    public CapCheckRow findLatestByContractAndStage(Long contractId, String paymentStage) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage);
        NativeQuery<?> query = prepare(FIND_LATEST_BY_CONTRACT_AND_STAGE, parameters);
        query.addScalar("capCheckId", Long.class);
        query.addScalar("contractId", Long.class);
        query.addScalar("paymentStage", String.class);
        query.addScalar("checkKind", String.class);
        query.addScalar("asOfDate", LocalDate.class);
        query.addScalar("capRuleSetId", Long.class);
        query.addScalar("refundRateTableId", Long.class);
        query.addScalar("basePremiumAmount", BigDecimal.class);
        query.addScalar("refund12mAmount", BigDecimal.class);
        query.addScalar("complianceDeductionAmount", BigDecimal.class);
        query.addScalar("limitAmount", BigDecimal.class);
        query.addScalar("includedAmount", BigDecimal.class);
        query.addScalar("remainingAmount", BigDecimal.class);
        query.addScalar("usagePct", BigDecimal.class);
        query.addScalar("resultStatus", String.class);
        query.addScalar("calculationSnapshotJson", String.class);
        return query.setTupleTransformer((tuple, aliases) -> {
            CapCheckRow row = new CapCheckRow();
            row.setCapCheckId((Long) tuple[0]);
            row.setContractId((Long) tuple[1]);
            row.setPaymentStage((String) tuple[2]);
            row.setCheckKind((String) tuple[3]);
            row.setAsOfDate((LocalDate) tuple[4]);
            row.setCapRuleSetId((Long) tuple[5]);
            row.setRefundRateTableId((Long) tuple[6]);
            row.setBasePremiumAmount((BigDecimal) tuple[7]);
            row.setRefund12mAmount((BigDecimal) tuple[8]);
            row.setComplianceDeductionAmount((BigDecimal) tuple[9]);
            row.setLimitAmount((BigDecimal) tuple[10]);
            row.setIncludedAmount((BigDecimal) tuple[11]);
            row.setRemainingAmount((BigDecimal) tuple[12]);
            row.setUsagePct((BigDecimal) tuple[13]);
            row.setResultStatus((String) tuple[14]);
            row.setCalculationSnapshotJson((String) tuple[15]);
            return row;
        }).getResultList().stream().findFirst().orElse(null);
    }

    public CapCheckRow findById(Long capCheckId) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("capCheckId", capCheckId);
        NativeQuery<?> query = prepare(FIND_BY_ID, parameters);
        query.addScalar("capCheckId", Long.class);
        query.addScalar("contractId", Long.class);
        query.addScalar("contractNo", String.class);
        query.addScalar("paymentStage", String.class);
        query.addScalar("checkKind", String.class);
        query.addScalar("asOfDate", LocalDate.class);
        query.addScalar("capRuleSetId", Long.class);
        query.addScalar("refundRateTableId", Long.class);
        query.addScalar("basePremiumAmount", BigDecimal.class);
        query.addScalar("refund12mAmount", BigDecimal.class);
        query.addScalar("complianceDeductionAmount", BigDecimal.class);
        query.addScalar("limitAmount", BigDecimal.class);
        query.addScalar("includedAmount", BigDecimal.class);
        query.addScalar("remainingAmount", BigDecimal.class);
        query.addScalar("usagePct", BigDecimal.class);
        query.addScalar("resultStatus", String.class);
        query.addScalar("calculationSnapshotJson", String.class);
        return query.setTupleTransformer((tuple, aliases) -> {
            CapCheckRow row = new CapCheckRow();
            row.setCapCheckId((Long) tuple[0]);
            row.setContractId((Long) tuple[1]);
            row.setContractNo((String) tuple[2]);
            row.setPaymentStage((String) tuple[3]);
            row.setCheckKind((String) tuple[4]);
            row.setAsOfDate((LocalDate) tuple[5]);
            row.setCapRuleSetId((Long) tuple[6]);
            row.setRefundRateTableId((Long) tuple[7]);
            row.setBasePremiumAmount((BigDecimal) tuple[8]);
            row.setRefund12mAmount((BigDecimal) tuple[9]);
            row.setComplianceDeductionAmount((BigDecimal) tuple[10]);
            row.setLimitAmount((BigDecimal) tuple[11]);
            row.setIncludedAmount((BigDecimal) tuple[12]);
            row.setRemainingAmount((BigDecimal) tuple[13]);
            row.setUsagePct((BigDecimal) tuple[14]);
            row.setResultStatus((String) tuple[15]);
            row.setCalculationSnapshotJson((String) tuple[16]);
            return row;
        }).getResultList().stream().findFirst().orElse(null);
    }

    public List<CapCheckListRow> search(LocalDate month, String paymentStage, String resultStatus, Long insurerId, Long organizationId, String contractNo, int offset, int limit) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("month", month);
        parameters.put("paymentStage", paymentStage);
        parameters.put("resultStatus", resultStatus);
        parameters.put("insurerId", insurerId);
        parameters.put("organizationId", organizationId);
        parameters.put("contractNo", contractNo);
        parameters.put("offset", offset);
        parameters.put("limit", limit);
        NativeQuery<?> query = prepare(SEARCH, parameters);
        query.addScalar("capCheckId", Long.class);
        query.addScalar("contractId", Long.class);
        query.addScalar("contractNo", String.class);
        query.addScalar("paymentStage", String.class);
        query.addScalar("asOfDate", LocalDate.class);
        query.addScalar("basePremiumAmount", BigDecimal.class);
        query.addScalar("refund12mAmount", BigDecimal.class);
        query.addScalar("complianceDeductionAmount", BigDecimal.class);
        query.addScalar("limitAmount", BigDecimal.class);
        query.addScalar("includedAmount", BigDecimal.class);
        query.addScalar("remainingAmount", BigDecimal.class);
        query.addScalar("usagePct", BigDecimal.class);
        query.addScalar("resultStatus", String.class);
        query.addScalar("capRuleSetId", Long.class);
        return query.setTupleTransformer((tuple, aliases) -> {
            CapCheckListRow row = new CapCheckListRow();
            row.setCapCheckId((Long) tuple[0]);
            row.setContractId((Long) tuple[1]);
            row.setContractNo((String) tuple[2]);
            row.setPaymentStage((String) tuple[3]);
            row.setAsOfDate((LocalDate) tuple[4]);
            row.setBasePremiumAmount((BigDecimal) tuple[5]);
            row.setRefund12mAmount((BigDecimal) tuple[6]);
            row.setComplianceDeductionAmount((BigDecimal) tuple[7]);
            row.setLimitAmount((BigDecimal) tuple[8]);
            row.setIncludedAmount((BigDecimal) tuple[9]);
            row.setRemainingAmount((BigDecimal) tuple[10]);
            row.setUsagePct((BigDecimal) tuple[11]);
            row.setResultStatus((String) tuple[12]);
            row.setCapRuleSetId((Long) tuple[13]);
            return row;
        }).getResultList();
    }

    public long count(LocalDate month, String paymentStage, String resultStatus, Long insurerId, Long organizationId, String contractNo) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("month", month);
        parameters.put("paymentStage", paymentStage);
        parameters.put("resultStatus", resultStatus);
        parameters.put("insurerId", insurerId);
        parameters.put("organizationId", organizationId);
        parameters.put("contractNo", contractNo);
        NativeQuery<?> query = prepare(COUNT, parameters);
        return ((Number) query.getSingleResult()).longValue();
    }

    public List<CapCheckStatusCount> summarize(LocalDate month, String paymentStage, Long insurerId, Long organizationId, String contractNo) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("month", month);
        parameters.put("paymentStage", paymentStage);
        parameters.put("insurerId", insurerId);
        parameters.put("organizationId", organizationId);
        parameters.put("contractNo", contractNo);
        NativeQuery<?> query = prepare(SUMMARIZE, parameters);
        query.addScalar("resultStatus", String.class);
        query.addScalar("count", Long.class);
        return query.setTupleTransformer((tuple, aliases) -> {
            CapCheckStatusCount row = new CapCheckStatusCount();
            row.setResultStatus((String) tuple[0]);
            row.setCount((Long) tuple[1]);
            return row;
        }).getResultList();
    }

    public List<CapStageSummaryRow> summarizeByStage(LocalDate month, Long insurerId, Long organizationId, String contractNo) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("month", month);
        parameters.put("insurerId", insurerId);
        parameters.put("organizationId", organizationId);
        parameters.put("contractNo", contractNo);
        NativeQuery<?> query = prepare(SUMMARIZE_BY_STAGE, parameters);
        query.addScalar("paymentStage", String.class);
        query.addScalar("contractCount", Long.class);
        query.addScalar("limitAmountTotal", BigDecimal.class);
        query.addScalar("includedAmountTotal", BigDecimal.class);
        query.addScalar("complianceDeductionAmountTotal", BigDecimal.class);
        query.addScalar("usagePct", BigDecimal.class);
        query.addScalar("violationCount", Long.class);
        query.addScalar("warningCount", Long.class);
        query.addScalar("reviewRequiredCount", Long.class);
        query.addScalar("worstContractNo", String.class);
        query.addScalar("worstUsagePct", BigDecimal.class);
        return query.setTupleTransformer((tuple, aliases) -> {
            CapStageSummaryRow row = new CapStageSummaryRow();
            row.setPaymentStage((String) tuple[0]);
            row.setContractCount((Long) tuple[1]);
            row.setLimitAmountTotal((BigDecimal) tuple[2]);
            row.setIncludedAmountTotal((BigDecimal) tuple[3]);
            row.setComplianceDeductionAmountTotal((BigDecimal) tuple[4]);
            row.setUsagePct((BigDecimal) tuple[5]);
            row.setViolationCount((Long) tuple[6]);
            row.setWarningCount((Long) tuple[7]);
            row.setReviewRequiredCount((Long) tuple[8]);
            row.setWorstContractNo((String) tuple[9]);
            row.setWorstUsagePct((BigDecimal) tuple[10]);
            return row;
        }).getResultList();
    }

    public List<CapAgentSummaryRow> summarizeByAgent(LocalDate month, Long insurerId, Long organizationId, String contractNo) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("month", month);
        parameters.put("insurerId", insurerId);
        parameters.put("organizationId", organizationId);
        parameters.put("contractNo", contractNo);
        NativeQuery<?> query = prepare(SUMMARIZE_BY_AGENT, parameters);
        query.addScalar("agentId", Long.class);
        query.addScalar("agentCode", String.class);
        query.addScalar("agentName", String.class);
        query.addScalar("organizationId", Long.class);
        query.addScalar("organizationCode", String.class);
        query.addScalar("organizationName", String.class);
        query.addScalar("contractCount", Long.class);
        query.addScalar("limitAmountTotal", BigDecimal.class);
        query.addScalar("includedAmountTotal", BigDecimal.class);
        query.addScalar("usagePct", BigDecimal.class);
        query.addScalar("violationCount", Long.class);
        query.addScalar("warningCount", Long.class);
        query.addScalar("reviewRequiredCount", Long.class);
        query.addScalar("worstContractNo", String.class);
        query.addScalar("worstUsagePct", BigDecimal.class);
        return query.setTupleTransformer((tuple, aliases) -> {
            CapAgentSummaryRow row = new CapAgentSummaryRow();
            row.setAgentId((Long) tuple[0]);
            row.setAgentCode((String) tuple[1]);
            row.setAgentName((String) tuple[2]);
            row.setOrganizationId((Long) tuple[3]);
            row.setOrganizationCode((String) tuple[4]);
            row.setOrganizationName((String) tuple[5]);
            row.setContractCount((Long) tuple[6]);
            row.setLimitAmountTotal((BigDecimal) tuple[7]);
            row.setIncludedAmountTotal((BigDecimal) tuple[8]);
            row.setUsagePct((BigDecimal) tuple[9]);
            row.setViolationCount((Long) tuple[10]);
            row.setWarningCount((Long) tuple[11]);
            row.setReviewRequiredCount((Long) tuple[12]);
            row.setWorstContractNo((String) tuple[13]);
            row.setWorstUsagePct((BigDecimal) tuple[14]);
            return row;
        }).getResultList();
    }

    public List<CapCheckDetailLine> findDetailsByCapCheckId(Long capCheckId) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("capCheckId", capCheckId);
        NativeQuery<?> query = prepare(FIND_DETAILS_BY_CAP_CHECK_ID, parameters);
        query.addScalar("detailSeq", Integer.class);
        query.addScalar("commissionItemId", Long.class);
        query.addScalar("itemCode", String.class);
        query.addScalar("itemName", String.class);
        query.addScalar("scheduleLineId", Long.class);
        query.addScalar("contractMonthNo", Integer.class);
        query.addScalar("classification", String.class);
        query.addScalar("amount", BigDecimal.class);
        query.addScalar("decisionReason", String.class);
        query.addScalar("evidenceRef", String.class);
        return query.setTupleTransformer((tuple, aliases) -> new CapCheckDetailLine(
                (Integer) tuple[0], (Long) tuple[1], (String) tuple[2], (String) tuple[3], (Long) tuple[4], (Integer) tuple[5], (String) tuple[6], (BigDecimal) tuple[7], (String) tuple[8], (String) tuple[9]))
                .getResultList();
    }

    public BigDecimal selectComplianceEvidenceAmount(Long contractId, PaymentStage paymentStage) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage.name());
        NativeQuery<?> query = prepare(SELECT_COMPLIANCE_EVIDENCE_AMOUNT, parameters);
        return (BigDecimal) query.getSingleResult();
    }

    public boolean existsApplicableRuleSet(Long contractId, PaymentStage paymentStage) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("contractId", contractId);
        parameters.put("paymentStage", paymentStage.name());
        NativeQuery<?> query = prepare(EXISTS_APPLICABLE_RULE_SET, parameters);
        return (Boolean) query.getSingleResult();
    }

    private NativeQuery<?> prepare(String template, Map<String, Object> parameters) {
        StringBuilder scope = new StringBuilder();
        appendFilter(scope, parameters, "month", "date_trunc('month', cc.as_of_date) = date_trunc('month', CAST(:month AS date))");
        appendFilter(scope, parameters, "insurerId", "ic.insurer_id = :insurerId");
        appendFilter(scope, parameters, "organizationId", "ic.organization_id = :organizationId");
        appendFilter(scope, parameters, "contractNo", "ic.contract_no = :contractNo");
        StringBuilder result = new StringBuilder(" WHERE 1 = 1");
        appendFilter(result, parameters, "paymentStage", "payment_stage = :paymentStage");
        appendFilter(result, parameters, "resultStatus", "result_status = :resultStatus");
        StringBuilder stage = new StringBuilder(" WHERE 1 = 1");
        appendFilter(stage, parameters, "paymentStage", "payment_stage = :paymentStage");
        String sql = template.replace("__latestScopedCapChecks__", LATEST_SCOPED_CAP_CHECKS)
                .replace("__scopeFilters__", scope.toString())
                .replace("__resultFilterWhere__", result.toString())
                .replace("__stageFilter__", stage.toString());
        NativeQuery<?> query = entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
        parameters.forEach((name, value) -> {
            if (query.getParameters().stream().anyMatch(parameter -> name.equals(parameter.getName()))) {
                query.setParameter(name, value);
            }
        });
        return query;
    }

    private void appendFilter(StringBuilder sql, Map<String, Object> parameters, String name, String condition) {
        if (parameters.get(name) != null) {
            sql.append(" AND ").append(condition);
        }
    }
}
