package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class PaymentCapExceptionBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "insertException" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "exceptionKey")));
                parameters.addValue("p1", parameter(value(input, "exceptionType")));
                parameters.addValue("p2", parameter(value(input, "exceptionType")));
                parameters.addValue("p3", parameter(value(input, "exceptionType")));
                parameters.addValue("p4", parameter(value(input, "exceptionType")));
                parameters.addValue("p5", parameter(value(input, "severity")));
                parameters.addValue("p6", parameter(value(input, "validationRunId")));
                parameters.addValue("p7", parameter(value(input, "contractId")));
                parameters.addValue("p8", parameter(value(input, "agentId")));
                parameters.addValue("p9", parameter(value(input, "policyVersionId")));
                parameters.addValue("p10", parameter(value(input, "paymentId")));
                parameters.addValue("p11", parameter(value(input, "capCheckId")));
                parameters.addValue("p12", parameter(value(input, "paymentId")));
                parameters.addValue("p13", parameter(value(input, "title")));
                parameters.addValue("p14", parameter(value(input, "description")));
                sql.append("""
                    INSERT INTO exception_case AS existing
                    (
                        exception_key,
                        exception_type,
                        reason_code,
                        severity,
                        status,
                        validation_run_id,
                        contract_id,
                        agent_id,
                        policy_version_id,
                        source_entity_type,
                        source_entity_id,
                        cap_check_id,
                        validation_month,
                        title,
                        description
                    )
                    VALUES
                        (
                        :p0,
                        :p1,

                        CASE
                            WHEN :p2 = 'CAP_VIOLATION' THEN 'CAP_LIMIT_VIOLATION'
                            WHEN :p3 = 'CAP_WARNING' THEN 'CAP_LIMIT_WARNING'
                            ELSE :p4
                        END,
                        :p5,
                        'NEW',
                        :p6,
                        :p7,
                        :p8,
                        :p9,

                        'COMMISSION_TRANSACTION',
                        CAST(:p10 AS varchar),

                        :p11,

                        (SELECT ct.settlement_month
                           FROM commission_transaction ct
                          WHERE ct.commission_transaction_id = :p12),
                        :p13,
                        :p14
                        )
                    ON CONFLICT
                        (exception_key)
                        DO UPDATE
                    SET
                        exception_type = EXCLUDED.exception_type,
                        reason_code = EXCLUDED.reason_code,
                        severity = EXCLUDED.severity,
                        validation_run_id = COALESCE(EXCLUDED.validation_run_id, existing.validation_run_id),
                        contract_id = EXCLUDED.contract_id,
                        agent_id = EXCLUDED.agent_id,

                        cap_check_id = EXCLUDED.cap_check_id,
                        validation_month = COALESCE(EXCLUDED.validation_month, existing.validation_month),
                        title = EXCLUDED.title,
                        description = EXCLUDED.description,
                        updated_at = clock_timestamp()
                           WHERE
                               existing.status IN ('NEW', 'IN_REVIEW')
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "selectExceptionForUpdate" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "exceptionCaseId")));
                sql.append("""
                    SELECT
                        exception_case_id,
                        status
                    FROM
                        exception_case
                    WHERE
                        exception_case_id = :p0
                    AND
                        exception_type IN ('CAP_WARNING', 'CAP_VIOLATION')
                    FOR UPDATE
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.transaction.performance.baseline.BaselineCapExceptionStatusRow.class), "", "");
            }
            case "insertExceptionAction" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "exceptionCaseId")));
                parameters.addValue("p1", parameter(value(input, "actionType")));
                parameters.addValue("p2", parameter(value(input, "reason")));
                parameters.addValue("p3", parameter(value(input, "evidenceRef")));
                parameters.addValue("p4", parameter(value(input, "actionBy")));
                parameters.addValue("p5", parameter(value(input, "exceptionCaseId")));
                sql.append("""
                    INSERT INTO exception_action
                    (
                        exception_case_id,
                        action_seq,
                        from_status,
                        to_status,
                        action_type,
                        reason,
                        evidence_ref,
                        action_by
                    )
                    SELECT
                        :p0,
                        COALESCE(MAX(action_seq), 0) + 1,
                        ec.status,
                        'RESOLVED',
                        :p1,
                        :p2,
                        :p3,
                        :p4
                    FROM
                        exception_case ec
                    LEFT JOIN
                        exception_action ea
                    ON
                        ea.exception_case_id = ec.exception_case_id
                    WHERE
                        ec.exception_case_id = :p5
                    GROUP BY
                        ec.status
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "updateExceptionResolved" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "exceptionCaseId")));
                sql.append("""
                    UPDATE
                        exception_case
                    SET
                        status = 'RESOLVED',
                        resolved_at = clock_timestamp(),
                        updated_at = clock_timestamp()
                    WHERE
                        exception_case_id = :p0
                    AND
                        status IN ('NEW', 'IN_REVIEW')
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "existsUnresolvedViolation" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "paymentId")));
                sql.append("""
                    SELECT
                        EXISTS
                        (
                            SELECT
                                1
                            FROM
                                exception_case
                            WHERE
                                source_entity_type = 'COMMISSION_TRANSACTION'
                            AND
                                source_entity_id = CAST(:p0 AS varchar)
                            AND
                                exception_type = 'CAP_VIOLATION'
                            AND
                                status IN ('NEW', 'IN_REVIEW')
                        )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Boolean.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
