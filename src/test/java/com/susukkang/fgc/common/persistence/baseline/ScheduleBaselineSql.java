package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class ScheduleBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "selectByCondition" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT
                        sh.schedule_header_id,
                        c.contract_no,
                        sh.payment_stage,
                        sh.schedule_regime,
                        sh.schedule_purpose,
                        sh.schedule_version_no,
                        sh.status,
                        sh.active_yn,
                        sh.generated_at,
                        sh.generation_reason,
                        COUNT(sl.schedule_line_id) AS line_count,
                        COALESCE(SUM(sl.expected_amount), 0) AS expected_total,
                        CONCAT(pv.policy_code,' v',pv.version_no) AS policy_version_label
                    FROM
                        schedule_header sh
                    JOIN
                        insurance_contract c
                            ON c.contract_id = sh.contract_id
                    JOIN policy_version pv
                            ON pv.policy_version_id = sh.policy_version_id
                    LEFT JOIN schedule_line sl
                            ON sl.schedule_header_id = sh.schedule_header_id
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "condition.contractNo") != null && !"".equals(value(input, "condition.contractNo"))) {
                    parameters.addValue("p0", parameter(value(input, "condition.contractNo")));
                    sql.append("""
                        AND c.contract_no
                        LIKE CONCAT('%', :p0, '%')
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.stage") != null) {
                    parameters.addValue("p1", parameter(value(input, "condition.stage")));
                    sql.append("""
                        AND sh.payment_stage = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.regime") != null) {
                    parameters.addValue("p2", parameter(value(input, "condition.regime")));
                    sql.append("""
                        AND sh.schedule_regime = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.purpose") != null) {
                    parameters.addValue("p3", parameter(value(input, "condition.purpose")));
                    sql.append("""
                        AND sh.schedule_purpose = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.status") != null) {
                    parameters.addValue("p4", parameter(value(input, "condition.status")));
                    sql.append("""
                        AND sh.status = :p4
                        """);
                    sql.append(" ");
                }
                parameters.addValue("p5", parameter(value(input, "size")));
                parameters.addValue("p6", parameter(value(input, "offset")));
                sql.append("""
                    GROUP BY
                        sh.schedule_header_id,
                        c.contract_no,
                        pv.policy_code,
                        pv.version_no
                    ORDER BY sh.schedule_header_id DESC
                    LIMIT :p5
                    OFFSET :p6
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse.class), "", "");
            }
            case "selectAllByCondition" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT sh.schedule_header_id,
                           c.contract_no,
                           sh.payment_stage,
                           sh.schedule_regime,
                           sh.schedule_purpose,
                           sh.schedule_version_no,
                           sh.status,
                           sh.active_yn,
                           sh.generated_at,
                           sh.generation_reason,
                           COUNT(sl.schedule_line_id) AS line_count,
                           COALESCE(SUM(sl.expected_amount), 0) AS expected_total,
                           CONCAT(pv.policy_code, ' v', pv.version_no) AS policy_version_label
                      FROM schedule_header sh
                      JOIN insurance_contract c ON c.contract_id = sh.contract_id
                      JOIN policy_version pv ON pv.policy_version_id = sh.policy_version_id
                      LEFT JOIN schedule_line sl ON sl.schedule_header_id = sh.schedule_header_id
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "condition.contractNo") != null && !"".equals(value(input, "condition.contractNo"))) {
                    parameters.addValue("p0", parameter(value(input, "condition.contractNo")));
                    sql.append("""
                        AND c.contract_no LIKE CONCAT('%', :p0, '%')
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.stage") != null) {
                    parameters.addValue("p1", parameter(value(input, "condition.stage")));
                    sql.append("""
                        AND sh.payment_stage = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.regime") != null) {
                    parameters.addValue("p2", parameter(value(input, "condition.regime")));
                    sql.append("""
                        AND sh.schedule_regime = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.purpose") != null) {
                    parameters.addValue("p3", parameter(value(input, "condition.purpose")));
                    sql.append("""
                        AND sh.schedule_purpose = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.status") != null) {
                    parameters.addValue("p4", parameter(value(input, "condition.status")));
                    sql.append("""
                        AND sh.status = :p4
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    GROUP BY sh.schedule_header_id, c.contract_no, pv.policy_code, pv.version_no
                    ORDER BY sh.schedule_header_id DESC
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse.class), "", "");
            }
            case "selectVersionsByScheduleHeaderId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "scheduleHeaderId")));
                sql.append("""
                    WITH target AS (
                        SELECT contract_id, payment_stage
                          FROM schedule_header
                         WHERE schedule_header_id = :p0
                    )
                    SELECT sh.schedule_header_id,
                           c.contract_no,
                           sh.payment_stage,
                           sh.schedule_regime,
                           sh.schedule_purpose,
                           sh.schedule_version_no,
                           sh.status,
                           sh.active_yn,
                           sh.generated_at,
                           sh.generation_reason,
                           COUNT(sl.schedule_line_id) AS line_count,
                           COALESCE(SUM(sl.expected_amount), 0) AS expected_total,
                           CONCAT(pv.policy_code, ' v', pv.version_no) AS policy_version_label
                      FROM schedule_header sh
                      JOIN target t
                        ON t.contract_id = sh.contract_id
                       AND t.payment_stage = sh.payment_stage
                      JOIN insurance_contract c
                        ON c.contract_id = sh.contract_id
                      JOIN policy_version pv
                        ON pv.policy_version_id = sh.policy_version_id
                      LEFT JOIN schedule_line sl
                        ON sl.schedule_header_id = sh.schedule_header_id
                     GROUP BY sh.schedule_header_id, c.contract_no, pv.policy_code, pv.version_no
                     ORDER BY sh.schedule_version_no DESC, sh.schedule_header_id DESC
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse.class), "", "");
            }
            case "countByCondition" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT
                        COUNT(*)
                    FROM
                        schedule_header sh
                    JOIN
                        insurance_contract c
                            ON c.contract_id = sh.contract_id
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "condition.contractNo") != null && !"".equals(value(input, "condition.contractNo"))) {
                    parameters.addValue("p0", parameter(value(input, "condition.contractNo")));
                    sql.append("""
                        AND c.contract_no
                        LIKE CONCAT('%', :p0, '%')
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.stage") != null) {
                    parameters.addValue("p1", parameter(value(input, "condition.stage")));
                    sql.append("""
                        AND sh.payment_stage = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.regime") != null) {
                    parameters.addValue("p2", parameter(value(input, "condition.regime")));
                    sql.append("""
                        AND sh.schedule_regime = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.purpose") != null) {
                    parameters.addValue("p3", parameter(value(input, "condition.purpose")));
                    sql.append("""
                        AND sh.schedule_purpose = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "condition.status") != null) {
                    parameters.addValue("p4", parameter(value(input, "condition.status")));
                    sql.append("""
                        AND sh.status = :p4
                        """);
                    sql.append(" ");
                }
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "selectScheduleDetailById" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "scheduleHeaderId")));
                sql.append("""
                    SELECT
                        -- 헤더 컬럼
                        sh.schedule_header_id,
                        c.contract_no,
                        i.insurer_name,
                        p.product_name,
                        sh.payment_stage,
                        sh.schedule_regime,
                        sh.schedule_purpose,
                        sh.schedule_version_no,
                        sh.status AS header_status,
                        sh.active_yn,
                        sh.generation_reason,
                        sh.generated_at,
                        COUNT(sl.schedule_line_id) OVER (PARTITION BY sh.schedule_header_id) AS line_count,
                        COALESCE(SUM(sl.expected_amount) OVER (PARTITION BY sh.schedule_header_id),0) AS expected_total,
                        CONCAT(pv.policy_code,' v',pv.version_no) AS policy_version_label,
                        -- 라인 컬럼
                        ci.cashflow_type,
                        c.contract_id,
                        sl.beneficiary_agent_id,
                        sl.commission_item_id,
                        (c.contract_date + make_interval(months => sl.contract_month_no - 1))::date AS attribution_date,
                        sl.line_no,
                        sl.installment_no,
                        sl.contract_month_no,
                        sl.due_date,
                        ci.item_name AS commission_item_name,
                        a.agent_name AS recipient_name,
                        sl.basis_code,
                        sl.basis_amount,
                        sl.calculation_type,
                        sl.rate_pct,
                        sl.expected_amount,
                        sl.line_status,
                        sl.source_commission_rule_id
                    -- 필요한 JOIN 컬럼들
                    FROM schedule_header sh
                         JOIN insurance_contract c
                              ON c.contract_id = sh.contract_id
                         JOIN insurer i
                              ON i.insurer_id = c.insurer_id
                         JOIN product_offering po
                              ON po.product_offering_id = c.product_offering_id
                         JOIN product p
                              ON p.product_id = po.product_id
                         JOIN policy_version pv
                              ON pv.policy_version_id = sh.policy_version_id
                         LEFT JOIN schedule_line sl
                               ON sl.schedule_header_id = sh.schedule_header_id
                         LEFT JOIN agent a
                               ON a.agent_id = sl.beneficiary_agent_id
                         LEFT JOIN commission_item ci
                               ON ci.commission_item_id = sl.commission_item_id
                    WHERE sh.schedule_header_id = :p0
                    ORDER BY sl.line_no
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scheduleRows(), "", "");
            }
            case "insertScheduleHeader" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                parameters.addValue("p2", parameter(value(input, "policyVersionId")));
                parameters.addValue("p3", parameter(value(input, "scheduleVersionNo")));
                parameters.addValue("p4", parameter(value(input, "schedulePurpose")));
                parameters.addValue("p5", parameter(value(input, "scenarioCode")));
                parameters.addValue("p6", parameter(value(input, "scheduleRegime")));
                parameters.addValue("p7", parameter(value(input, "status")));
                parameters.addValue("p8", parameter(value(input, "activeYn")));
                parameters.addValue("p9", parameter(value(input, "generationReason")));
                parameters.addValue("p10", parameter(value(input, "regeneratedFromId")));
                sql.append("""
                    INSERT INTO fgc.schedule_header
                    (
                        contract_id,
                        payment_stage,
                        policy_version_id,
                        schedule_version_no,
                        schedule_purpose,
                        scenario_code,
                        schedule_regime,
                        status,
                        active_yn,
                        generation_reason,
                        regenerated_from_id
                    )
                    VALUES
                        (
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
                            :p10
                        )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "scheduleHeaderId", "schedule_header_id");
            }
            case "insertAllScheduleLines" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    INSERT INTO fgc.schedule_line
                    (
                    schedule_header_id,
                    line_no,
                    installment_no,
                    contract_month_no,
                    due_date,
                    commission_item_id,
                    beneficiary_agent_id,
                    basis_code,
                    basis_amount,
                    calculation_type,
                    rate_pct,
                    fixed_amount,
                    expected_amount,
                    rounding_scale,
                    rounding_mode,
                    payment_condition_code,
                    line_status,
                    source_commission_rule_id
                    )
                    VALUES
                    """);
                sql.append(" ");
                sql.append("");
                int indexitem0 = 0;
                for (Object item0 : (java.util.Collection<?>) value(input, "lines")) {
                    if (indexitem0 > 0) sql.append(",");
                    parameters.addValue("p1_" + indexitem0, parameter(value(item0, "scheduleHeaderId")));
                    parameters.addValue("p2_" + indexitem0, parameter(value(item0, "lineNo")));
                    parameters.addValue("p3_" + indexitem0, parameter(value(item0, "installmentNo")));
                    parameters.addValue("p4_" + indexitem0, parameter(value(item0, "contractMonthNo")));
                    parameters.addValue("p5_" + indexitem0, parameter(value(item0, "dueDate")));
                    parameters.addValue("p6_" + indexitem0, parameter(value(item0, "commissionItemId")));
                    parameters.addValue("p7_" + indexitem0, parameter(value(item0, "beneficiaryAgentId")));
                    parameters.addValue("p8_" + indexitem0, parameter(value(item0, "basisCode")));
                    parameters.addValue("p9_" + indexitem0, parameter(value(item0, "basisAmount")));
                    parameters.addValue("p10_" + indexitem0, parameter(value(item0, "calculationType")));
                    parameters.addValue("p11_" + indexitem0, parameter(value(item0, "ratePct")));
                    parameters.addValue("p12_" + indexitem0, parameter(value(item0, "fixedAmount")));
                    parameters.addValue("p13_" + indexitem0, parameter(value(item0, "expectedAmount")));
                    parameters.addValue("p14_" + indexitem0, parameter(value(item0, "roundingScale")));
                    parameters.addValue("p15_" + indexitem0, parameter(value(item0, "roundingMode")));
                    parameters.addValue("p16_" + indexitem0, parameter(value(item0, "paymentConditionCode")));
                    parameters.addValue("p17_" + indexitem0, parameter(value(item0, "lineStatus")));
                    parameters.addValue("p18_" + indexitem0, parameter(value(item0, "sourceCommissionRuleId")));
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
                        ,

                        """);
                    sql.append(":p13_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p14_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p15_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p16_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p17_").append(indexitem0);
                    sql.append("""
                        ,

                        """);
                    sql.append(":p18_").append(indexitem0);
                    sql.append("""

                                    )
                        """);
                    sql.append(" ");
                    indexitem0++;
                }
                sql.append("");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "linkHeadersToValidationRun" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                sql.append("""
                    UPDATE fgc.schedule_header
                    SET validation_run_id = :p0
                    WHERE schedule_header_id IN
                    """);
                sql.append(" ");
                sql.append("(");
                int indexitem1 = 0;
                for (Object item1 : (java.util.Collection<?>) value(input, "scheduleHeaderIds")) {
                    if (indexitem1 > 0) sql.append(",");
                    parameters.addValue("p2_" + indexitem1, parameter(item1));
                    sql.append(":p2_").append(indexitem1);
                    sql.append(" ");
                    indexitem1++;
                }
                sql.append(")");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "upsertPolicyReviewCase" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                parameters.addValue("p2", parameter(value(input, "exceptionType")));
                parameters.addValue("p3", parameter(value(input, "exceptionType")));
                parameters.addValue("p4", parameter(value(input, "exceptionType")));
                parameters.addValue("p5", parameter(value(input, "contractId")));
                parameters.addValue("p6", parameter(value(input, "contractId")));
                parameters.addValue("p7", parameter(value(input, "title")));
                parameters.addValue("p8", parameter(value(input, "description")));
                sql.append("""
                    INSERT INTO fgc.exception_case
                    (
                        exception_key,
                        exception_type,
                        reason_code,
                        severity,
                        status,
                        contract_id,
                        source_entity_type,
                        source_entity_id,
                        title,
                        description
                    )
                    VALUES
                    (
                        CONCAT(
                            'SCHEDULE_POLICY:',
                            :p0,
                            ':',
                            :p1,
                            ':',
                            :p2
                        ),
                        :p3,
                        :p4,
                        'HIGH',
                        'NEW',
                        :p5,
                        'INSURANCE_CONTRACT',
                        CAST(:p6 AS varchar),
                        :p7,
                        :p8
                    )
                    ON CONFLICT (exception_key)
                    DO UPDATE SET
                        status = 'NEW',
                        reason_code = EXCLUDED.reason_code,
                        title = EXCLUDED.title,
                        description = EXCLUDED.description,
                        resolved_at = NULL,
                        updated_at = clock_timestamp()
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "lockContractForScheduleGeneration" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                sql.append("""
                    SELECT contract_id
                    FROM fgc.insurance_contract
                    WHERE contract_id = :p0
                    FOR UPDATE
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "selectActiveOperationalPolicyVersionId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                sql.append("""
                    SELECT policy_version_id
                    FROM fgc.schedule_header
                    WHERE contract_id = :p0
                      AND payment_stage = :p1
                      AND schedule_purpose = 'OPERATIONAL'
                      AND active_yn = TRUE
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "selectNextScheduleVersionNo" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                sql.append("""
                    SELECT COALESCE(MAX(schedule_version_no), 0) + 1
                    FROM fgc.schedule_header
                    WHERE contract_id = :p0
                      AND payment_stage = :p1
                      AND schedule_purpose = 'OPERATIONAL'
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Integer.class), "", "");
            }
            case "deactivateActiveOperationalSchedule" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                sql.append("""
                    UPDATE fgc.schedule_header
                    SET active_yn = FALSE
                    WHERE contract_id = :p0
                      AND payment_stage = :p1
                      AND schedule_purpose = 'OPERATIONAL'
                      AND active_yn = TRUE
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "selectActiveOperationalScheduleIds" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                sql.append("""
                    SELECT schedule_header_id
                    FROM fgc.schedule_header
                    WHERE contract_id = :p0
                      AND schedule_purpose = 'OPERATIONAL'
                      AND active_yn = TRUE
                    ORDER BY payment_stage
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "selectByContractIdAndPaymentStage" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                sql.append("""
                    SELECT
                        sh.schedule_header_id,
                        c.contract_no,
                        sh.payment_stage,
                        sh.schedule_regime,
                        sh.schedule_purpose,
                        sh.schedule_version_no,
                        sh.status AS header_status,
                        sh.active_yn,
                        sh.generation_reason,
                        sh.generated_at,
                        COUNT(sl.schedule_line_id) OVER (PARTITION BY sh.schedule_header_id) AS line_count,
                        COALESCE(SUM(sl.expected_amount) OVER (PARTITION BY sh.schedule_header_id), 0) AS expected_total,
                        CONCAT(
                                pv.policy_code,
                                ' v',
                                pv.version_no
                        ) AS policy_version_label,
                        ci.cashflow_type,
                        c.contract_id,
                        sl.beneficiary_agent_id,
                        sl.commission_item_id,
                        (c.contract_date + make_interval(months => sl.contract_month_no - 1))::date AS attribution_date,
                        sl.line_no,
                        sl.installment_no,
                        sl.contract_month_no,
                        sl.due_date,
                        ci.item_name AS commission_item_name,
                        a.agent_name AS recipient_name,
                        sl.basis_code,
                        sl.basis_amount,
                        sl.calculation_type,
                        sl.rate_pct,
                        sl.expected_amount,
                        sl.line_status,
                        sl.source_commission_rule_id
                    FROM fgc.schedule_header sh
                             JOIN fgc.insurance_contract c
                                  ON c.contract_id = sh.contract_id
                             JOIN fgc.policy_version pv
                                  ON pv.policy_version_id = sh.policy_version_id
                             LEFT JOIN fgc.schedule_line sl
                                       ON sl.schedule_header_id = sh.schedule_header_id
                             LEFT JOIN fgc.agent a
                                       ON a.agent_id = sl.beneficiary_agent_id
                             LEFT JOIN fgc.commission_item ci
                                       ON ci.commission_item_id = sl.commission_item_id
                    WHERE sh.contract_id = :p0
                      AND sh.payment_stage = :p1
                      AND sh.schedule_purpose = 'OPERATIONAL'
                      AND sh.active_yn = TRUE
                    ORDER BY sl.line_no
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scheduleRows(), "", "");
            }
            case "selectScheduleHeaderById" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "scheduleId")));
                sql.append("""
                    SELECT
                        schedule_header_id,
                        contract_id,
                        payment_stage,
                        policy_version_id,
                        schedule_version_no,
                        schedule_purpose,
                        scenario_code,
                        schedule_regime,
                        status,
                        active_yn,
                        generation_reason,
                        regenerated_from_id
                    FROM
                        schedule_header
                    WHERE
                        schedule_header_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.schedule.dto.ScheduleHeaderInsertDTO.class), "", "");
            }
            case "selectScheduleLinesByScheduleId" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "scheduleId")));
                sql.append("""
                    SELECT
                        schedule_line_id,
                        schedule_header_id,
                        line_no,
                        installment_no,
                        contract_month_no,
                        due_date,
                        commission_item_id,
                        beneficiary_agent_id,
                        basis_code,
                        basis_amount,
                        calculation_type,
                        rate_pct,
                        fixed_amount,
                        rounding_scale,
                        rounding_mode,
                        expected_amount,
                        payment_condition_code,
                        line_status,
                        source_commission_rule_id
                    FROM schedule_line
                    WHERE schedule_header_id = :p0
                    ORDER BY line_no
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.schedule.dto.ScheduleLineInsertDTO.class), "", "");
            }
            case "updateScheduleHeaderStatus" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "scheduleHeaderStatus")));
                parameters.addValue("p1", parameter(value(input, "active")));
                parameters.addValue("p2", parameter(value(input, "scheduleHeaderId")));
                sql.append("""
                    UPDATE
                        schedule_header
                    SET
                        status = :p0,
                        active_yn = :p1
                    WHERE
                        schedule_header_id = :p2
                    AND
                        active_yn = TRUE
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "confirmPlannedScheduleLines" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "scheduleHeaderId")));
                sql.append("""
                    UPDATE schedule_line
                       SET line_status = 'CONFIRMED'
                     WHERE schedule_header_id = :p0
                       AND line_status = 'PLANNED'
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "confirmScheduleHeader" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "scheduleHeaderId")));
                sql.append("""
                    UPDATE schedule_header
                       SET status = 'CONFIRMED'
                     WHERE schedule_header_id = :p0
                       AND status = 'PLANNED'
                       AND active_yn = TRUE
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
