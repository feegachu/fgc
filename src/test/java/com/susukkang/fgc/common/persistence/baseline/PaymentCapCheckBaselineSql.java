package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class PaymentCapCheckBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "insertCapCheck" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                parameters.addValue("p1", parameter(value(input, "contractId")));
                parameters.addValue("p2", parameter(value(input, "paymentStage")));
                parameters.addValue("p3", parameter(value(input, "capRuleSetId")));
                parameters.addValue("p4", parameter(value(input, "refundRateTableId")));
                parameters.addValue("p5", parameter(value(input, "checkKind")));
                parameters.addValue("p6", parameter(value(input, "asOfDate")));
                parameters.addValue("p7", parameter(value(input, "basePremiumAmount")));
                parameters.addValue("p8", parameter(value(input, "refund12mAmount")));
                parameters.addValue("p9", parameter(value(input, "complianceDeductionAmount")));
                parameters.addValue("p10", parameter(value(input, "limitAmount")));
                parameters.addValue("p11", parameter(value(input, "includedAmount")));
                parameters.addValue("p12", parameter(value(input, "remainingAmount")));
                parameters.addValue("p13", parameter(value(input, "usagePct")));
                parameters.addValue("p14", parameter(value(input, "resultStatus")));
                parameters.addValue("p15", parameter(value(input, "calculationSnapshotJson")));
                sql.append("""
                    INSERT INTO fgc.cap_check (
                        validation_run_id, contract_id, payment_stage, cap_rule_set_id, refund_rate_table_id,
                        check_kind, as_of_date, base_premium_amount, refund_12m_amount, compliance_deduction_amount,
                        limit_amount, included_amount, remaining_amount, usage_pct, result_status, calculation_snapshot
                    ) VALUES (
                        :p0, :p1, :p2, :p3, :p4,
                        :p5, :p6, :p7, :p8, :p9,
                        :p10, :p11, :p12, :p13, :p14,
                        COALESCE(:p15, '{}')::jsonb
                    )
                    ON CONFLICT ON CONSTRAINT uq_cap_check_monthly DO UPDATE SET
                        cap_rule_set_id              = EXCLUDED.cap_rule_set_id,
                        refund_rate_table_id         = EXCLUDED.refund_rate_table_id,
                        check_kind                   = EXCLUDED.check_kind,
                        as_of_date                   = EXCLUDED.as_of_date,
                        base_premium_amount          = EXCLUDED.base_premium_amount,
                        refund_12m_amount            = EXCLUDED.refund_12m_amount,
                        compliance_deduction_amount  = EXCLUDED.compliance_deduction_amount,
                        limit_amount                 = EXCLUDED.limit_amount,
                        included_amount              = EXCLUDED.included_amount,
                        remaining_amount             = EXCLUDED.remaining_amount,
                        usage_pct                    = EXCLUDED.usage_pct,
                        result_status                = EXCLUDED.result_status,
                        calculation_snapshot         = EXCLUDED.calculation_snapshot,
                        checked_at                   = clock_timestamp()
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "capCheckId", "cap_check_id");
            }
            case "pruneCapCheckDetails" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "capCheckId")));
                parameters.addValue("p1", parameter(value(input, "maxDetailSeq")));
                sql.append("""
                    DELETE FROM fgc.cap_check_detail
                     WHERE cap_check_id = :p0
                       AND detail_seq > :p1
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "insertCapCheckDetails" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    INSERT INTO fgc.cap_check_detail (
                        cap_check_id, detail_seq, commission_item_id, item_code, item_name,
                        schedule_line_id, transaction_attribution_id, contract_month_no, classification_snapshot, amount, decision_reason, evidence_ref
                    ) VALUES
                    """);
                sql.append(" ");
                sql.append("");
                int indexitem0 = 0;
                for (Object item0 : (java.util.Collection<?>) value(input, "details")) {
                    if (indexitem0 > 0) sql.append(",");
                    parameters.addValue("p1_" + indexitem0, parameter(value(item0, "capCheckId")));
                    parameters.addValue("p2_" + indexitem0, parameter(value(item0, "detailSeq")));
                    parameters.addValue("p3_" + indexitem0, parameter(value(item0, "commissionItemId")));
                    parameters.addValue("p4_" + indexitem0, parameter(value(item0, "itemCode")));
                    parameters.addValue("p5_" + indexitem0, parameter(value(item0, "itemName")));
                    parameters.addValue("p6_" + indexitem0, parameter(value(item0, "scheduleLineId")));
                    parameters.addValue("p7_" + indexitem0, parameter(value(item0, "transactionAttributionId")));
                    parameters.addValue("p8_" + indexitem0, parameter(value(item0, "contractMonthNo")));
                    parameters.addValue("p9_" + indexitem0, parameter(value(item0, "classificationSnapshot")));
                    parameters.addValue("p10_" + indexitem0, parameter(value(item0, "amount")));
                    parameters.addValue("p11_" + indexitem0, parameter(value(item0, "decisionReason")));
                    parameters.addValue("p12_" + indexitem0, parameter(value(item0, "evidenceRef")));
                    sql.append("""
                        (
                        """);
                    sql.append(":p1_").append(indexitem0);
                    sql.append("""
                        ,
                        """);
                    sql.append(":p2_").append(indexitem0);
                    sql.append("""
                        ,
                        """);
                    sql.append(":p3_").append(indexitem0);
                    sql.append("""
                        ,
                        """);
                    sql.append(":p4_").append(indexitem0);
                    sql.append("""
                        ,
                        """);
                    sql.append(":p5_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p6_").append(indexitem0);
                    sql.append("""
                        ,
                        """);
                    sql.append(":p7_").append(indexitem0);
                    sql.append("""
                        ,
                        """);
                    sql.append(":p8_").append(indexitem0);
                    sql.append("""
                        ,
                        """);
                    sql.append(":p9_").append(indexitem0);
                    sql.append("""
                        ,
                        """);
                    sql.append(":p10_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p11_").append(indexitem0);
                    sql.append("""
                        ,
                        """);
                    sql.append(":p12_").append(indexitem0);
                    sql.append("""
                        )
                        """);
                    sql.append(" ");
                    indexitem0++;
                }
                sql.append("");
                sql.append("""
                    ON CONFLICT ON CONSTRAINT uq_cap_check_detail DO UPDATE SET
                        commission_item_id      = EXCLUDED.commission_item_id,
                        item_code                = EXCLUDED.item_code,
                        item_name                = EXCLUDED.item_name,
                        schedule_line_id         = EXCLUDED.schedule_line_id,
                        transaction_attribution_id = EXCLUDED.transaction_attribution_id,
                        contract_month_no        = EXCLUDED.contract_month_no,
                        classification_snapshot  = EXCLUDED.classification_snapshot,
                        amount                   = EXCLUDED.amount,
                        decision_reason          = EXCLUDED.decision_reason,
                        evidence_ref             = EXCLUDED.evidence_ref
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "findLatestByContractAndStage" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                sql.append("""
                    SELECT cc.cap_check_id                 AS capCheckId,
                           cc.contract_id                   AS contractId,
                           cc.payment_stage                 AS paymentStage,
                           cc.check_kind                    AS checkKind,
                           cc.as_of_date                    AS asOfDate,
                           cc.cap_rule_set_id               AS capRuleSetId,
                           cc.refund_rate_table_id          AS refundRateTableId,
                           cc.base_premium_amount           AS basePremiumAmount,
                           cc.refund_12m_amount             AS refund12mAmount,
                           cc.compliance_deduction_amount   AS complianceDeductionAmount,
                           cc.limit_amount                  AS limitAmount,
                           cc.included_amount               AS includedAmount,
                           cc.remaining_amount              AS remainingAmount,
                           cc.usage_pct                     AS usagePct,
                           cc.result_status                 AS resultStatus,
                           cc.calculation_snapshot::text    AS calculationSnapshotJson
                      FROM fgc.cap_check cc
                     WHERE cc.contract_id = :p0
                       AND cc.payment_stage = :p1

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
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapCheckRow.class), "", "");
            }
            case "findById" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "capCheckId")));
                sql.append("""
                    SELECT cc.cap_check_id                 AS capCheckId,
                           cc.contract_id                   AS contractId,
                           ic.contract_no                   AS contractNo,
                           cc.payment_stage                 AS paymentStage,
                           cc.check_kind                    AS checkKind,
                           cc.as_of_date                    AS asOfDate,
                           cc.cap_rule_set_id               AS capRuleSetId,
                           cc.refund_rate_table_id          AS refundRateTableId,
                           cc.base_premium_amount           AS basePremiumAmount,
                           cc.refund_12m_amount             AS refund12mAmount,
                           cc.compliance_deduction_amount   AS complianceDeductionAmount,
                           cc.limit_amount                  AS limitAmount,
                           cc.included_amount               AS includedAmount,
                           cc.remaining_amount              AS remainingAmount,
                           cc.usage_pct                     AS usagePct,
                           cc.result_status                 AS resultStatus,
                           cc.calculation_snapshot::text    AS calculationSnapshotJson
                      FROM fgc.cap_check cc
                      JOIN fgc.insurance_contract ic ON ic.contract_id = cc.contract_id
                     WHERE cc.cap_check_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapCheckRow.class), "", "");
            }
            case "search" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
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
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                sql.append("""
                    AND (
                        cc.candidate_transaction_id IS NULL
                        OR EXISTS (
                            SELECT 1
                              FROM fgc.commission_transaction candidate_ct
                             WHERE candidate_ct.commission_transaction_id = cc.candidate_transaction_id
                               AND candidate_ct.status = 'CONFIRMED'
                        )
                    )
                    """);
                sql.append(" ");
                if (value(input, "month") != null) {
                    parameters.addValue("p0", parameter(value(input, "month")));
                    sql.append("""
                        AND date_trunc('month', cc.as_of_date) = date_trunc('month', :p0::date)
                        """);
                    sql.append(" ");
                }
                if (value(input, "insurerId") != null) {
                    parameters.addValue("p1", parameter(value(input, "insurerId")));
                    sql.append("""
                        AND ic.insurer_id = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "organizationId") != null) {
                    parameters.addValue("p2", parameter(value(input, "organizationId")));
                    sql.append("""
                        AND ic.organization_id = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "contractNo") != null) {
                    parameters.addValue("p3", parameter(value(input, "contractNo")));
                    sql.append("""
                        AND ic.contract_no = :p3
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    ), latest_cap_check AS (
                        SELECT *
                          FROM ranked_cap_check
                         WHERE latest_rank = 1
                    )
                    """);
                sql.append(" ");
                sql.append("""
                    SELECT cap_check_id                 AS capCheckId,
                           contract_id                  AS contractId,
                           contract_no                  AS contractNo,
                           payment_stage                AS paymentStage,
                           as_of_date                   AS asOfDate,
                           base_premium_amount          AS basePremiumAmount,
                           refund_12m_amount            AS refund12mAmount,
                           compliance_deduction_amount  AS complianceDeductionAmount,
                           limit_amount                 AS limitAmount,
                           included_amount              AS includedAmount,
                           remaining_amount             AS remainingAmount,
                           usage_pct                    AS usagePct,
                           result_status                AS resultStatus,
                           cap_rule_set_id              AS capRuleSetId
                      FROM latest_cap_check
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "paymentStage") != null) {
                    parameters.addValue("p4", parameter(value(input, "paymentStage")));
                    sql.append("""
                        AND payment_stage = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "resultStatus") != null) {
                    parameters.addValue("p5", parameter(value(input, "resultStatus")));
                    sql.append("""
                        AND result_status = :p5
                        """);
                    sql.append(" ");
                }
                parameters.addValue("p6", parameter(value(input, "limit")));
                parameters.addValue("p7", parameter(value(input, "offset")));
                sql.append("""
                    ORDER BY as_of_date DESC, cap_check_id DESC
                    LIMIT :p6 OFFSET :p7
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapCheckListRow.class), "", "");
            }
            case "count" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
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
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                sql.append("""
                    AND (
                        cc.candidate_transaction_id IS NULL
                        OR EXISTS (
                            SELECT 1
                              FROM fgc.commission_transaction candidate_ct
                             WHERE candidate_ct.commission_transaction_id = cc.candidate_transaction_id
                               AND candidate_ct.status = 'CONFIRMED'
                        )
                    )
                    """);
                sql.append(" ");
                if (value(input, "month") != null) {
                    parameters.addValue("p0", parameter(value(input, "month")));
                    sql.append("""
                        AND date_trunc('month', cc.as_of_date) = date_trunc('month', :p0::date)
                        """);
                    sql.append(" ");
                }
                if (value(input, "insurerId") != null) {
                    parameters.addValue("p1", parameter(value(input, "insurerId")));
                    sql.append("""
                        AND ic.insurer_id = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "organizationId") != null) {
                    parameters.addValue("p2", parameter(value(input, "organizationId")));
                    sql.append("""
                        AND ic.organization_id = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "contractNo") != null) {
                    parameters.addValue("p3", parameter(value(input, "contractNo")));
                    sql.append("""
                        AND ic.contract_no = :p3
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    ), latest_cap_check AS (
                        SELECT *
                          FROM ranked_cap_check
                         WHERE latest_rank = 1
                    )
                    """);
                sql.append(" ");
                sql.append("""
                    SELECT COUNT(*)
                      FROM latest_cap_check
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "paymentStage") != null) {
                    parameters.addValue("p4", parameter(value(input, "paymentStage")));
                    sql.append("""
                        AND payment_stage = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "resultStatus") != null) {
                    parameters.addValue("p5", parameter(value(input, "resultStatus")));
                    sql.append("""
                        AND result_status = :p5
                        """);
                    sql.append(" ");
                }
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "summarize" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
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
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                sql.append("""
                    AND (
                        cc.candidate_transaction_id IS NULL
                        OR EXISTS (
                            SELECT 1
                              FROM fgc.commission_transaction candidate_ct
                             WHERE candidate_ct.commission_transaction_id = cc.candidate_transaction_id
                               AND candidate_ct.status = 'CONFIRMED'
                        )
                    )
                    """);
                sql.append(" ");
                if (value(input, "month") != null) {
                    parameters.addValue("p0", parameter(value(input, "month")));
                    sql.append("""
                        AND date_trunc('month', cc.as_of_date) = date_trunc('month', :p0::date)
                        """);
                    sql.append(" ");
                }
                if (value(input, "insurerId") != null) {
                    parameters.addValue("p1", parameter(value(input, "insurerId")));
                    sql.append("""
                        AND ic.insurer_id = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "organizationId") != null) {
                    parameters.addValue("p2", parameter(value(input, "organizationId")));
                    sql.append("""
                        AND ic.organization_id = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "contractNo") != null) {
                    parameters.addValue("p3", parameter(value(input, "contractNo")));
                    sql.append("""
                        AND ic.contract_no = :p3
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    ), latest_cap_check AS (
                        SELECT *
                          FROM ranked_cap_check
                         WHERE latest_rank = 1
                    )
                    """);
                sql.append(" ");
                sql.append("""
                    SELECT result_status AS resultStatus, COUNT(*) AS count
                      FROM latest_cap_check
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "paymentStage") != null) {
                    parameters.addValue("p4", parameter(value(input, "paymentStage")));
                    sql.append("""
                        AND payment_stage = :p4
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    GROUP BY result_status
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapCheckStatusCount.class), "", "");
            }
            case "summarizeByStage" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
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
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                sql.append("""
                    AND (
                        cc.candidate_transaction_id IS NULL
                        OR EXISTS (
                            SELECT 1
                              FROM fgc.commission_transaction candidate_ct
                             WHERE candidate_ct.commission_transaction_id = cc.candidate_transaction_id
                               AND candidate_ct.status = 'CONFIRMED'
                        )
                    )
                    """);
                sql.append(" ");
                if (value(input, "month") != null) {
                    parameters.addValue("p0", parameter(value(input, "month")));
                    sql.append("""
                        AND date_trunc('month', cc.as_of_date) = date_trunc('month', :p0::date)
                        """);
                    sql.append(" ");
                }
                if (value(input, "insurerId") != null) {
                    parameters.addValue("p1", parameter(value(input, "insurerId")));
                    sql.append("""
                        AND ic.insurer_id = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "organizationId") != null) {
                    parameters.addValue("p2", parameter(value(input, "organizationId")));
                    sql.append("""
                        AND ic.organization_id = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "contractNo") != null) {
                    parameters.addValue("p3", parameter(value(input, "contractNo")));
                    sql.append("""
                        AND ic.contract_no = :p3
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    ), latest_cap_check AS (
                        SELECT *
                          FROM ranked_cap_check
                         WHERE latest_rank = 1
                    )
                    """);
                sql.append(" ");
                sql.append("""
                    SELECT payment_stage AS paymentStage,
                           COUNT(*) AS contractCount,
                           COALESCE(SUM(ROUND(limit_amount, 0)), 0) AS limitAmountTotal,
                           COALESCE(SUM(ROUND(included_amount, 0)), 0) AS includedAmountTotal,
                           COALESCE(SUM(
                               CASE WHEN payment_stage = 'INSURER_TO_GA'
                                    THEN ROUND(compliance_deduction_amount, 0)
                                    ELSE 0
                               END
                           ), 0) AS complianceDeductionAmountTotal,
                           CASE WHEN COALESCE(SUM(ROUND(limit_amount, 0)), 0) = 0
                                THEN 0.000000::numeric(12,6)
                                ELSE ROUND(
                                    SUM(ROUND(included_amount, 0)) * 100 /
                                    SUM(ROUND(limit_amount, 0)), 6)
                           END AS usagePct,
                           COUNT(*) FILTER (WHERE result_status = 'VIOLATION') AS violationCount,
                           COUNT(*) FILTER (WHERE result_status = 'WARNING') AS warningCount,
                           COUNT(*) FILTER (WHERE result_status = 'REVIEW_REQUIRED') AS reviewRequiredCount,
                           (ARRAY_AGG(contract_no ORDER BY
                                CASE result_status
                                    WHEN 'VIOLATION' THEN 4
                                    WHEN 'WARNING' THEN 3
                                    WHEN 'REVIEW_REQUIRED' THEN 2
                                    ELSE 1
                                END DESC,
                                usage_pct DESC NULLS LAST,
                                cap_check_id DESC))[1] AS worstContractNo,
                           (ARRAY_AGG(usage_pct ORDER BY
                                CASE result_status
                                    WHEN 'VIOLATION' THEN 4
                                    WHEN 'WARNING' THEN 3
                                    WHEN 'REVIEW_REQUIRED' THEN 2
                                    ELSE 1
                                END DESC,
                                usage_pct DESC NULLS LAST,
                                cap_check_id DESC))[1] AS worstUsagePct
                      FROM latest_cap_check
                     GROUP BY payment_stage
                     ORDER BY CASE payment_stage WHEN 'INSURER_TO_GA' THEN 1 ELSE 2 END
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapStageSummaryRow.class), "", "");
            }
            case "summarizeByAgent" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
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
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                sql.append("""
                    AND (
                        cc.candidate_transaction_id IS NULL
                        OR EXISTS (
                            SELECT 1
                              FROM fgc.commission_transaction candidate_ct
                             WHERE candidate_ct.commission_transaction_id = cc.candidate_transaction_id
                               AND candidate_ct.status = 'CONFIRMED'
                        )
                    )
                    """);
                sql.append(" ");
                if (value(input, "month") != null) {
                    parameters.addValue("p0", parameter(value(input, "month")));
                    sql.append("""
                        AND date_trunc('month', cc.as_of_date) = date_trunc('month', :p0::date)
                        """);
                    sql.append(" ");
                }
                if (value(input, "insurerId") != null) {
                    parameters.addValue("p1", parameter(value(input, "insurerId")));
                    sql.append("""
                        AND ic.insurer_id = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "organizationId") != null) {
                    parameters.addValue("p2", parameter(value(input, "organizationId")));
                    sql.append("""
                        AND ic.organization_id = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "contractNo") != null) {
                    parameters.addValue("p3", parameter(value(input, "contractNo")));
                    sql.append("""
                        AND ic.contract_no = :p3
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    ), latest_cap_check AS (
                        SELECT *
                          FROM ranked_cap_check
                         WHERE latest_rank = 1
                    )
                    """);
                sql.append(" ");
                sql.append("""
                    SELECT a.agent_id AS agentId,
                           a.agent_code AS agentCode,
                           a.agent_name AS agentName,
                           o.organization_id AS organizationId,
                           o.organization_code AS organizationCode,
                           o.organization_name AS organizationName,
                           COUNT(*) AS contractCount,
                           COALESCE(SUM(ROUND(lcc.limit_amount, 0)), 0) AS limitAmountTotal,
                           COALESCE(SUM(ROUND(lcc.included_amount, 0)), 0) AS includedAmountTotal,
                           CASE WHEN COALESCE(SUM(ROUND(lcc.limit_amount, 0)), 0) = 0
                                THEN 0.000000::numeric(12,6)
                                ELSE ROUND(
                                    SUM(ROUND(lcc.included_amount, 0)) * 100 /
                                    SUM(ROUND(lcc.limit_amount, 0)), 6)
                           END AS usagePct,
                           COUNT(*) FILTER (WHERE lcc.result_status = 'VIOLATION') AS violationCount,
                           COUNT(*) FILTER (WHERE lcc.result_status = 'WARNING') AS warningCount,
                           COUNT(*) FILTER (WHERE lcc.result_status = 'REVIEW_REQUIRED') AS reviewRequiredCount,
                           (ARRAY_AGG(lcc.contract_no ORDER BY
                                CASE lcc.result_status
                                    WHEN 'VIOLATION' THEN 4
                                    WHEN 'WARNING' THEN 3
                                    WHEN 'REVIEW_REQUIRED' THEN 2
                                    ELSE 1
                                END DESC,
                                lcc.usage_pct DESC NULLS LAST,
                                lcc.cap_check_id DESC))[1] AS worstContractNo,
                           (ARRAY_AGG(lcc.usage_pct ORDER BY
                                CASE lcc.result_status
                                    WHEN 'VIOLATION' THEN 4
                                    WHEN 'WARNING' THEN 3
                                    WHEN 'REVIEW_REQUIRED' THEN 2
                                    ELSE 1
                                END DESC,
                                lcc.usage_pct DESC NULLS LAST,
                                lcc.cap_check_id DESC))[1] AS worstUsagePct
                      FROM latest_cap_check lcc
                      JOIN fgc.agent a ON a.agent_id = lcc.agent_id
                      JOIN fgc.organization o ON o.organization_id = lcc.organization_id
                     WHERE lcc.payment_stage = 'GA_TO_FC'
                     GROUP BY a.agent_id, a.agent_code, a.agent_name,
                              o.organization_id, o.organization_code, o.organization_name
                     ORDER BY SUM(ROUND(lcc.included_amount, 0)) DESC, a.agent_code
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapAgentSummaryRow.class), "", "");
            }
            case "findDetailsByCapCheckId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "capCheckId")));
                sql.append("""
                    SELECT ccd.detail_seq             AS detailSeq,
                           ccd.commission_item_id      AS commissionItemId,
                           ccd.item_code                AS itemCode,
                           ccd.item_name                AS itemName,
                           ccd.schedule_line_id        AS scheduleLineId,
                           ccd.contract_month_no        AS contractMonthNo,
                           ccd.classification_snapshot AS classification,
                           ccd.amount                  AS amount,
                           ccd.decision_reason         AS decisionReason,
                           ccd.evidence_ref            AS evidenceRef
                      FROM fgc.cap_check_detail ccd
                     WHERE ccd.cap_check_id = :p0
                     ORDER BY ccd.detail_seq
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapCheckDetailLine.class), "", "");
            }
            case "selectComplianceEvidenceAmount" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                sql.append("""
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
                    WHERE ta.contract_id = :p0
                      AND ta.exclusion_type_snapshot = 'COMPLIANCE_3PCT'
                      AND NULLIF(BTRIM(ta.evidence_ref), '') IS NOT NULL
                      AND ct.payment_stage = :p1
                      AND ct.status = 'CONFIRMED'
                      AND ta.attribution_date >= c.contract_date
                      AND ta.attribution_date < c.contract_date + INTERVAL '12 months'
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(java.math.BigDecimal.class), "", "");
            }
            case "existsApplicableRuleSet" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentStage")));
                parameters.addValue("p1", parameter(value(input, "contractId")));
                sql.append("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM fgc.insurance_contract c
                          JOIN fgc.product_offering po
                            ON po.product_offering_id = c.product_offering_id
                          JOIN fgc.product p
                            ON p.product_id = po.product_id
                          JOIN fgc.cap_rule_set crs
                            ON crs.payment_stage = :p0
                           AND crs.contract_date_from <= c.contract_date
                           AND (crs.contract_date_to IS NULL OR crs.contract_date_to >= c.contract_date)
                           AND (crs.insurer_id IS NULL OR crs.insurer_id = c.insurer_id)
                           AND (crs.product_group_code IS NULL OR crs.product_group_code = p.product_group_code)
                           AND (crs.channel_code IS NULL OR crs.channel_code = po.channel_code)
                          JOIN fgc.policy_version pv
                            ON pv.policy_version_id = crs.policy_version_id
                           AND pv.status = 'ACTIVE'
                         WHERE c.contract_id = :p1
                    )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Boolean.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
