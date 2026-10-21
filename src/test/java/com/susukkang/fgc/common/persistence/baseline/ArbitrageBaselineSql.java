package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class ArbitrageBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "selectByCondition" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT
                        ac.arbitrage_check_id,
                        ac.contract_id,
                        c.contract_no,
                        ac.payment_stage,
                        ac.as_of_date,
                        ac.contract_month_no,
                        ac.cumulative_paid_premium,
                        ac.paid_commission_amount,
                        ac.planned_commission_amount,
                        ac.included_surrender_value_amount,
                        ac.net_difference_amount,
                        ac.refund_addition_applied_yn,
                        ac.surrender_value_source_type,
                        ac.result_status,
                        ac.calculation_snapshot ->> 'decisionReason' AS decision_reason
                    FROM
                    """);
                sql.append(" ");
                sql.append("""
                    (SELECT DISTINCT ON (contract_id, payment_stage, as_of_date) *
                       FROM arbitrage_check
                      ORDER BY contract_id, payment_stage, as_of_date, arbitrage_check_id DESC)
                    """);
                sql.append(" ");
                sql.append("""
                    ac
                           JOIN
                               insurance_contract c
                           ON
                               c.contract_id = ac.contract_id
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "condition.month") != null) {
                    parameters.addValue("p0", parameter(value(input, "condition.monthStart")));
                    parameters.addValue("p1", parameter(value(input, "condition.nextMonthStart")));
                    sql.append("""
                        AND ac.as_of_date  >=  :p0
                        AND ac.as_of_date  <  :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.status") != null) {
                    parameters.addValue("p2", parameter(value(input, "condition.status")));
                    sql.append("""
                        AND ac.result_status = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.stage") != null) {
                    parameters.addValue("p3", parameter(value(input, "condition.stage")));
                    sql.append("""
                        AND ac.payment_stage = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.insurerId") != null) {
                    parameters.addValue("p4", parameter(value(input, "condition.insurerId")));
                    sql.append("""
                        AND c.insurer_id = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.contractNo") != null && !"".equals(value(input, "condition.contractNo"))) {
                    parameters.addValue("p5", parameter(value(input, "condition.contractNo")));
                    sql.append("""
                        AND c.contract_no = :p5
                        """);
                    sql.append(" ");
                }
                parameters.addValue("p6", parameter(value(input, "size")));
                parameters.addValue("p7", parameter(value(input, "offset")));
                sql.append("""
                    ORDER BY
                        ac.arbitrage_check_id DESC
                    LIMIT :p6
                    OFFSET :p7
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.arbitrage.dto.ArbitrageCheckView.class), "", "");
            }
            case "arbitrageCheckSummary" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT
                        COUNT(*) FILTER (WHERE ac.result_status = 'CLEAR') AS clear_count,
                        COUNT(*) FILTER (WHERE ac.result_status = 'CANDIDATE') AS candidate_count,
                        COUNT(*) FILTER (WHERE ac.result_status = 'REVIEW_REQUIRED') AS review_required_count
                    FROM
                    """);
                sql.append(" ");
                sql.append("""
                    (SELECT DISTINCT ON (contract_id, payment_stage, as_of_date) *
                       FROM arbitrage_check
                      ORDER BY contract_id, payment_stage, as_of_date, arbitrage_check_id DESC)
                    """);
                sql.append(" ");
                sql.append("""
                    ac
                           JOIN
                               insurance_contract c
                           ON
                               c.contract_id = ac.contract_id
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "condition.month") != null) {
                    parameters.addValue("p0", parameter(value(input, "condition.monthStart")));
                    parameters.addValue("p1", parameter(value(input, "condition.nextMonthStart")));
                    sql.append("""
                        AND ac.as_of_date  >=  :p0
                        AND ac.as_of_date  <  :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.status") != null) {
                    parameters.addValue("p2", parameter(value(input, "condition.status")));
                    sql.append("""
                        AND ac.result_status = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.stage") != null) {
                    parameters.addValue("p3", parameter(value(input, "condition.stage")));
                    sql.append("""
                        AND ac.payment_stage = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.insurerId") != null) {
                    parameters.addValue("p4", parameter(value(input, "condition.insurerId")));
                    sql.append("""
                        AND c.insurer_id = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.contractNo") != null && !"".equals(value(input, "condition.contractNo"))) {
                    parameters.addValue("p5", parameter(value(input, "condition.contractNo")));
                    sql.append("""
                        AND c.contract_no = :p5
                        """);
                    sql.append(" ");
                }
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.arbitrage.dto.ArbitrageCheckSummary.class), "", "");
            }
            case "countByCondition" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT COUNT(*)
                    FROM
                    """);
                sql.append(" ");
                sql.append("""
                    (SELECT DISTINCT ON (contract_id, payment_stage, as_of_date) *
                       FROM arbitrage_check
                      ORDER BY contract_id, payment_stage, as_of_date, arbitrage_check_id DESC)
                    """);
                sql.append(" ");
                sql.append("""
                    ac
                           JOIN insurance_contract c
                             ON c.contract_id = ac.contract_id
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "condition.month") != null) {
                    parameters.addValue("p0", parameter(value(input, "condition.monthStart")));
                    parameters.addValue("p1", parameter(value(input, "condition.nextMonthStart")));
                    sql.append("""
                        AND ac.as_of_date  >=  :p0
                        AND ac.as_of_date  <  :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.status") != null) {
                    parameters.addValue("p2", parameter(value(input, "condition.status")));
                    sql.append("""
                        AND ac.result_status = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.stage") != null) {
                    parameters.addValue("p3", parameter(value(input, "condition.stage")));
                    sql.append("""
                        AND ac.payment_stage = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.insurerId") != null) {
                    parameters.addValue("p4", parameter(value(input, "condition.insurerId")));
                    sql.append("""
                        AND c.insurer_id = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.contractNo") != null && !"".equals(value(input, "condition.contractNo"))) {
                    parameters.addValue("p5", parameter(value(input, "condition.contractNo")));
                    sql.append("""
                        AND c.contract_no = :p5
                        """);
                    sql.append(" ");
                }
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "selectCalculationSource" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "asOfDate")));
                parameters.addValue("p1", parameter(value(input, "contractId")));
                sql.append("""
                    SELECT
                        c.contract_id,
                        c.contract_date,
                        c.insurer_id,
                        p.product_id,
                        c.product_offering_id,
                        c.payment_term_months,
                        po.channel_code,
                        po.standard_deduction_80_yn,
                        c.standard_surrender_deduction_amount,
                        fs.as_of_date AS snapshot_as_of_date,
                        fs.contract_month_no,
                        fs.cumulative_paid_premium,
                        fs.surrender_value,
                        fs.surrender_value_type,
                        fs.refund_rate_table_id AS snapshot_refund_rate_table_id,
                        p.protection_type,
                        termination.termination_date
                    FROM
                        insurance_contract c
                    JOIN
                        product_offering po
                    ON
                        po.product_offering_id = c.product_offering_id
                    JOIN
                        product p
                    ON
                        p.product_id = po.product_id
                    LEFT JOIN LATERAL (
                        SELECT MIN((e.effective_at AT TIME ZONE 'Asia/Seoul')::date) AS termination_date
                          FROM fgc.contract_status_event e
                         WHERE e.contract_id = c.contract_id AND e.new_status IN ('TERMINATED', 'LAPSED')
                           AND (e.effective_at AT TIME ZONE 'Asia/Seoul')::date <= :p0
                           AND NOT EXISTS (
                               SELECT 1 FROM fgc.contract_status_event resumed
                                WHERE resumed.contract_id = e.contract_id
                                  AND resumed.new_status NOT IN ('TERMINATED', 'LAPSED')
                                  AND (resumed.effective_at AT TIME ZONE 'Asia/Seoul')::date <= :p0
                                  AND (resumed.effective_at, resumed.event_seq, resumed.contract_status_event_id)
                                      > (e.effective_at, e.event_seq, e.contract_status_event_id)
                           )
                    ) termination ON true
                    LEFT JOIN LATERAL (
                        SELECT
                            cfs.as_of_date,
                            cfs.contract_month_no,
                            cfs.cumulative_paid_premium,
                            cfs.surrender_value,
                            cfs.surrender_value_type,
                            cfs.refund_rate_table_id
                        FROM
                            contract_financial_snapshot cfs
                        WHERE
                            cfs.contract_id = c.contract_id
                        AND
                            cfs.as_of_date <= COALESCE(termination.termination_date, :p0)
                        ORDER BY
                            cfs.as_of_date DESC,
                            CASE WHEN cfs.surrender_value_type = 'ACTUAL' THEN 0 ELSE 1 END,
                            cfs.contract_financial_snapshot_id DESC
                        LIMIT 1
                    ) fs ON true
                    WHERE
                        c.contract_id = :p1
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.arbitrage.dto.ArbitrageCalculationSource.class), "", "");
            }
            case "sumConfirmedCommissionAmount" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                parameters.addValue("p2", parameter(value(input, "asOfDate")));
                sql.append("""
                    SELECT
                        COALESCE(SUM(CASE WHEN ct.cashflow_type = 'PAYMENT'
                            THEN ROUND(ta.attributed_amount, 0) ELSE 0 END), 0) AS confirmed_payment_amount,
                        COALESCE(SUM(CASE WHEN ct.cashflow_type = 'DEDUCTION'
                            THEN ROUND(ta.attributed_amount, 0) ELSE 0 END), 0) AS confirmed_deduction_amount,
                        COALESCE(SUM(
                            CASE
                                WHEN ct.cashflow_type = 'DEDUCTION'
                                    THEN -ROUND(ta.attributed_amount, 0)
                                ELSE ROUND(ta.attributed_amount, 0)
                            END
                        ), 0) AS paid_commission_amount
                    FROM
                        transaction_attribution ta
                    JOIN
                        commission_transaction ct
                    ON
                        ct.commission_transaction_id = ta.commission_transaction_id
                    WHERE
                        ta.contract_id = :p0
                    AND
                        ct.payment_stage = :p1
                    AND
                        ct.status = 'CONFIRMED'
                    AND
                        ta.attribution_date  <=  :p2
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.arbitrage.dto.ConfirmedCommissionSummary.class), "", "");
            }
            case "sumPlannedCommissionAmount" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                sql.append("""
                    SELECT
                        COALESCE(SUM(ROUND(sl.expected_amount, 0)), 0)
                    FROM
                        schedule_header sh
                    JOIN
                        schedule_line sl
                    ON
                        sl.schedule_header_id = sh.schedule_header_id
                    WHERE
                        sh.contract_id = :p0
                    AND
                        sh.payment_stage = :p1
                    AND
                        sh.active_yn = true
                    AND
                        sh.schedule_purpose = 'OPERATIONAL'
                    AND
                        sl.line_status = 'PLANNED'
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(java.math.BigDecimal.class), "", "");
            }
            case "selectRefundRateCandidates" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "source.insurerId")));
                parameters.addValue("p1", parameter(value(input, "source.productId")));
                parameters.addValue("p2", parameter(value(input, "source.paymentTermMonths")));
                parameters.addValue("p3", parameter(value(input, "source.channelCode")));
                parameters.addValue("p4", parameter(value(input, "source.productOfferingId")));
                parameters.addValue("p5", parameter(value(input, "source.contractDate")));
                parameters.addValue("p6", parameter(value(input, "source.contractDate")));
                parameters.addValue("p7", parameter(value(input, "source.contractMonthNo")));
                sql.append("""
                    SELECT
                        rrt.refund_rate_table_id,
                        rrl.refund_rate_pct
                    FROM
                        refund_rate_table rrt
                    JOIN
                        refund_rate_line rrl
                    ON
                        rrl.refund_rate_table_id = rrt.refund_rate_table_id
                    WHERE
                        rrt.insurer_id = :p0
                    AND
                        rrt.product_id = :p1
                    AND
                        rrt.payment_term_months = :p2
                    AND
                        rrt.channel_code = :p3
                    AND
                        (rrt.product_offering_id IS NULL
                            OR rrt.product_offering_id = :p4)
                    AND
                        rrt.standard_deduction_80_yn = true
                    AND
                        rrt.effective_from  <=  :p5
                    AND
                        (rrt.effective_to IS NULL OR rrt.effective_to  >=  :p6)
                    AND
                        rrl.contract_month_no = :p7
                    ORDER BY
                        CASE WHEN rrt.product_offering_id IS NOT NULL THEN 0 ELSE 1 END,
                        rrt.effective_from DESC,
                        rrt.refund_rate_table_id DESC
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.arbitrage.dto.ArbitrageRefundRateCandidate.class), "", "");
            }
            case "insertArbitrageCheck" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                parameters.addValue("p1", parameter(value(input, "contractId")));
                parameters.addValue("p2", parameter(value(input, "paymentStage")));
                parameters.addValue("p3", parameter(value(input, "asOfDate")));
                parameters.addValue("p4", parameter(value(input, "contractMonthNo")));
                parameters.addValue("p5", parameter(value(input, "cumulativePaidPremium")));
                parameters.addValue("p6", parameter(value(input, "paidCommissionAmount")));
                parameters.addValue("p7", parameter(value(input, "plannedCommissionAmount")));
                parameters.addValue("p8", parameter(value(input, "includedSurrenderValueAmount")));
                parameters.addValue("p9", parameter(value(input, "refundAdditionAppliedYn")));
                parameters.addValue("p10", parameter(value(input, "surrenderValueSourceType")));
                parameters.addValue("p11", parameter(value(input, "netDifferenceAmount")));
                parameters.addValue("p12", parameter(value(input, "refundRateTableId")));
                parameters.addValue("p13", parameter(value(input, "standardDeduction80Yn")));
                parameters.addValue("p14", parameter(value(input, "resultStatus")));
                parameters.addValue("p15", parameter(value(input, "calculationSnapshot")));
                sql.append("""
                    INSERT INTO arbitrage_check (
                        validation_run_id,
                        contract_id,
                        payment_stage,
                        as_of_date,
                        contract_month_no,
                        cumulative_paid_premium,
                        paid_commission_amount,
                        planned_commission_amount,
                        included_surrender_value_amount,
                        refund_addition_applied_yn,
                        surrender_value_source_type,
                        net_difference_amount,
                        refund_rate_table_id,
                        standard_deduction_80_yn,
                        result_status,
                        calculation_snapshot
                    ) VALUES (
                        :p0,
                        :p1,
                        :p2,
                        :p3,
                        :p4,
                        :p5,
                        :p6,
                        :p7,
                        :p8,
                        :p9,
                        :p10,
                        :p11,
                        :p12,
                        :p13,
                        :p14,
                        :p15::jsonb
                    )
                    ON CONFLICT ON CONSTRAINT uq_arbitrage_check
                    DO UPDATE SET
                        contract_month_no = EXCLUDED.contract_month_no,
                        cumulative_paid_premium = EXCLUDED.cumulative_paid_premium,
                        paid_commission_amount = EXCLUDED.paid_commission_amount,
                        planned_commission_amount = EXCLUDED.planned_commission_amount,
                        included_surrender_value_amount = EXCLUDED.included_surrender_value_amount,
                        refund_addition_applied_yn = EXCLUDED.refund_addition_applied_yn,
                        surrender_value_source_type = EXCLUDED.surrender_value_source_type,
                        net_difference_amount = EXCLUDED.net_difference_amount,
                        refund_rate_table_id = EXCLUDED.refund_rate_table_id,
                        standard_deduction_80_yn = EXCLUDED.standard_deduction_80_yn,
                        result_status = EXCLUDED.result_status,
                        calculation_snapshot = EXCLUDED.calculation_snapshot
                    RETURNING arbitrage_check_id
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "arbitrageCheckId", "arbitrage_check_id");
            }
            case "selectByContractId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT
                        ac.arbitrage_check_id,
                        ac.contract_id,
                        c.contract_no,
                        ac.payment_stage,
                        ac.as_of_date,
                        ac.contract_month_no,
                        ac.cumulative_paid_premium,
                        ac.paid_commission_amount,
                        ac.planned_commission_amount,
                        ac.included_surrender_value_amount,
                        ac.net_difference_amount,
                        ac.refund_addition_applied_yn,
                        ac.surrender_value_source_type,
                        ac.result_status,
                        ac.calculation_snapshot ->> 'decisionReason' AS decision_reason
                    FROM
                    """);
                sql.append(" ");
                sql.append("""
                    (SELECT DISTINCT ON (contract_id, payment_stage, as_of_date) *
                       FROM arbitrage_check
                      ORDER BY contract_id, payment_stage, as_of_date, arbitrage_check_id DESC)
                    """);
                sql.append(" ");
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                sql.append("""
                    ac
                           JOIN
                               insurance_contract c
                           ON
                               c.contract_id = ac.contract_id
                           WHERE
                               ac.contract_id = :p0
                           AND
                               ac.payment_stage = :p1
                           ORDER BY
                               ac.as_of_date ASC,
                               ac.arbitrage_check_id ASC
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.arbitrage.dto.ArbitrageCheckView.class), "", "");
            }
            case "selectSelectedContractIds" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                sql.append("""
                    SELECT
                        vt.contract_id
                    FROM
                        validation_target vt
                    WHERE
                        vt.validation_run_id = :p0
                    AND
                        vt.selection_status = 'SELECTED'
                    ORDER BY
                        vt.contract_id
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
