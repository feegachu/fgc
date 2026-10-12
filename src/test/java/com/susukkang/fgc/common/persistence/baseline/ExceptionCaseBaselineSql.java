package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class ExceptionCaseBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "search" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT ec.exception_case_id  AS exceptionCaseId,
                           ec.exception_key      AS exceptionKey,
                           ec.exception_type     AS exceptionType,
                           ec.reason_code        AS reasonCode,
                           ec.severity,
                           ec.status,
                           ec.title,
                           ec.description,
                           ec.contract_id         AS contractId,
                           ic.contract_no        AS contractNo,
                           ag.agent_name         AS agentName,
                           ec.assigned_to        AS assignedTo,
                           assignee.login_id     AS assigneeLoginId,
                           ec.source_entity_type AS sourceEntityType,
                           ec.source_entity_id   AS sourceEntityId,
                           ec.cap_check_id       AS capCheckId,
                           reconciliation.result_type AS reconciliationResultType,
                           ec.validation_month   AS validationMonth,
                           ec.first_detected_run_id AS firstDetectedRunId,
                           ec.last_detected_run_id  AS lastDetectedRunId,
                           ec.first_detected_at  AS firstDetectedAt,
                           ec.last_detected_at   AS lastDetectedAt,
                           ec.detection_count    AS detectionCount,
                           ec.created_at         AS createdAt
                      FROM fgc.exception_case ec
                      LEFT JOIN fgc.insurance_contract ic ON ic.contract_id = ec.contract_id
                      LEFT JOIN fgc.agent ag ON ag.agent_id = ec.agent_id
                      LEFT JOIN fgc.app_user assignee ON assignee.user_id = ec.assigned_to
                      LEFT JOIN fgc.reconciliation_result reconciliation
                             ON ec.source_entity_type = 'RECONCILIATION_RESULT'
                            AND ec.source_entity_id = CAST(reconciliation.reconciliation_result_id AS varchar)
                     WHERE 1 = 1
                    """);
                sql.append(" ");
                if (value(input, "criteria.type") != null) {
                    parameters.addValue("p0", parameter(value(input, "criteria.type")));
                    sql.append("""
                        AND ec.exception_type = :p0
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.reasonCode") != null && !((String) value(input, "criteria.reasonCode")).isBlank()) {
                    parameters.addValue("p1", parameter(value(input, "criteria.reasonCode")));
                    sql.append("""
                        AND ec.reason_code = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.severity") != null) {
                    parameters.addValue("p2", parameter(value(input, "criteria.severity")));
                    sql.append("""
                        AND ec.severity = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.validationRunId") != null) {
                    parameters.addValue("p3", parameter(value(input, "criteria.validationRunId")));
                    sql.append("""
                        AND ec.validation_run_id = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.types") != null && !((java.util.Collection<?>) value(input, "criteria.types")).isEmpty()) {
                    sql.append("""
                        AND ec.exception_type IN
                        """);
                    sql.append(" ");
                    sql.append("(");
                    int indexitem4 = 0;
                    for (Object item4 : (java.util.Collection<?>) value(input, "criteria.types")) {
                        if (indexitem4 > 0) sql.append(",");
                        parameters.addValue("p5_" + indexitem4, parameter(item4));
                        sql.append(":p5_").append(indexitem4);
                        sql.append(" ");
                        indexitem4++;
                    }
                    sql.append(")");
                }
                if (value(input, "statuses") != null && !((java.util.Collection<?>) value(input, "statuses")).isEmpty()) {
                    sql.append("""
                        AND ec.status IN
                        """);
                    sql.append(" ");
                    sql.append("(");
                    int indexitem6 = 0;
                    for (Object item6 : (java.util.Collection<?>) value(input, "statuses")) {
                        if (indexitem6 > 0) sql.append(",");
                        parameters.addValue("p7_" + indexitem6, parameter(item6));
                        sql.append(":p7_").append(indexitem6);
                        sql.append(" ");
                        indexitem6++;
                    }
                    sql.append(")");
                }
                if (Boolean.TRUE.equals(value(input, "criteria.unassignedOnly"))) {
                    sql.append("""
                        AND ec.assigned_to IS NULL
                        """);
                    sql.append(" ");
                }
                else if (value(input, "criteria.assignee") != null) {
                    parameters.addValue("p8", parameter(value(input, "criteria.assignee")));
                    sql.append("""
                        AND ec.assigned_to = :p8
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.contractNo") != null && !((String) value(input, "criteria.contractNo")).isBlank()) {
                    parameters.addValue("p9", parameter(value(input, "criteria.contractNo")));
                    sql.append("""
                        AND ic.contract_no ILIKE CONCAT('%', :p9, '%')
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.validationMonth") != null) {
                    parameters.addValue("p10", parameter(value(input, "criteria.validationMonth")));
                    sql.append("""
                        AND ec.validation_month = :p10
                        """);
                    sql.append(" ");
                }
                parameters.addValue("p11", parameter(value(input, "limit")));
                parameters.addValue("p12", parameter(value(input, "offset")));
                sql.append("""
                    ORDER BY CASE ec.severity
                               WHEN 'CRITICAL' THEN 0 WHEN 'HIGH' THEN 1
                               WHEN 'WARNING' THEN 2 ELSE 3
                             END,
                             ec.created_at DESC,
                             ec.exception_case_id DESC
                    LIMIT :p11 OFFSET :p12
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.exceptioncase.dto.ExceptionCaseSearchRow.class), "", "");
            }
            case "findOccurrencesByCaseIds" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT occurrence.exception_occurrence_id AS exceptionOccurrenceId,
                           occurrence.exception_case_id       AS exceptionCaseId,
                           occurrence.validation_run_id       AS validationRunId,
                           vr.run_no                          AS runNo,
                           vr.validation_month                AS validationMonth,
                           occurrence.exception_type          AS exceptionType,
                           occurrence.reason_code             AS reasonCode,
                           occurrence.source_entity_type      AS sourceEntityType,
                           occurrence.source_entity_id        AS sourceEntityId,
                           occurrence.evidence_snapshot::text AS evidenceJson,
                           occurrence.is_new_case             AS newCase,
                           occurrence.was_reopened            AS reopened,
                           occurrence.detected_at             AS detectedAt
                      FROM fgc.exception_occurrence occurrence
                      JOIN fgc.validation_run vr ON vr.validation_run_id = occurrence.validation_run_id
                     WHERE occurrence.exception_case_id IN
                    """);
                sql.append(" ");
                sql.append("(");
                int indexitem0 = 0;
                for (Object item0 : (java.util.Collection<?>) value(input, "exceptionCaseIds")) {
                    if (indexitem0 > 0) sql.append(",");
                    parameters.addValue("p1_" + indexitem0, parameter(item0));
                    sql.append(":p1_").append(indexitem0);
                    sql.append(" ");
                    indexitem0++;
                }
                sql.append(")");
                sql.append("""
                    ORDER BY occurrence.exception_case_id, occurrence.detected_at DESC,
                             occurrence.exception_occurrence_id DESC
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.exceptioncase.dto.ExceptionOccurrenceRow.class), "", "");
            }
            case "count" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT COUNT(*)
                      FROM fgc.exception_case ec
                      LEFT JOIN fgc.insurance_contract ic ON ic.contract_id = ec.contract_id
                     WHERE 1 = 1
                    """);
                sql.append(" ");
                if (value(input, "criteria.type") != null) {
                    parameters.addValue("p0", parameter(value(input, "criteria.type")));
                    sql.append("""
                        AND ec.exception_type = :p0
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.reasonCode") != null && !((String) value(input, "criteria.reasonCode")).isBlank()) {
                    parameters.addValue("p1", parameter(value(input, "criteria.reasonCode")));
                    sql.append("""
                        AND ec.reason_code = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.severity") != null) {
                    parameters.addValue("p2", parameter(value(input, "criteria.severity")));
                    sql.append("""
                        AND ec.severity = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.validationRunId") != null) {
                    parameters.addValue("p3", parameter(value(input, "criteria.validationRunId")));
                    sql.append("""
                        AND ec.validation_run_id = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.types") != null && !((java.util.Collection<?>) value(input, "criteria.types")).isEmpty()) {
                    sql.append("""
                        AND ec.exception_type IN
                        """);
                    sql.append(" ");
                    sql.append("(");
                    int indexitem4 = 0;
                    for (Object item4 : (java.util.Collection<?>) value(input, "criteria.types")) {
                        if (indexitem4 > 0) sql.append(",");
                        parameters.addValue("p5_" + indexitem4, parameter(item4));
                        sql.append(":p5_").append(indexitem4);
                        sql.append(" ");
                        indexitem4++;
                    }
                    sql.append(")");
                }
                if (value(input, "statuses") != null && !((java.util.Collection<?>) value(input, "statuses")).isEmpty()) {
                    sql.append("""
                        AND ec.status IN
                        """);
                    sql.append(" ");
                    sql.append("(");
                    int indexitem6 = 0;
                    for (Object item6 : (java.util.Collection<?>) value(input, "statuses")) {
                        if (indexitem6 > 0) sql.append(",");
                        parameters.addValue("p7_" + indexitem6, parameter(item6));
                        sql.append(":p7_").append(indexitem6);
                        sql.append(" ");
                        indexitem6++;
                    }
                    sql.append(")");
                }
                if (Boolean.TRUE.equals(value(input, "criteria.unassignedOnly"))) {
                    sql.append("""
                        AND ec.assigned_to IS NULL
                        """);
                    sql.append(" ");
                }
                else if (value(input, "criteria.assignee") != null) {
                    parameters.addValue("p8", parameter(value(input, "criteria.assignee")));
                    sql.append("""
                        AND ec.assigned_to = :p8
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.contractNo") != null && !((String) value(input, "criteria.contractNo")).isBlank()) {
                    parameters.addValue("p9", parameter(value(input, "criteria.contractNo")));
                    sql.append("""
                        AND ic.contract_no ILIKE CONCAT('%', :p9, '%')
                        """);
                    sql.append(" ");
                }
                if (value(input, "criteria.validationMonth") != null) {
                    parameters.addValue("p10", parameter(value(input, "criteria.validationMonth")));
                    sql.append("""
                        AND ec.validation_month = :p10
                        """);
                    sql.append(" ");
                }
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "findActionsByCaseIds" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT ea.exception_action_id AS exceptionActionId,
                           ea.exception_case_id   AS exceptionCaseId,
                           ea.action_seq          AS actionSeq,
                           ea.from_status         AS fromStatus,
                           ea.to_status           AS toStatus,
                           ea.action_type         AS actionType,
                           ea.reason,
                           ea.evidence_ref        AS evidenceRef,
                           ea.action_by           AS actionBy,
                           actor.login_id         AS actionByLoginId,
                           ea.action_at           AS actionAt
                      FROM fgc.exception_action ea
                      JOIN fgc.app_user actor ON actor.user_id = ea.action_by
                     WHERE ea.exception_case_id IN
                    """);
                sql.append(" ");
                sql.append("(");
                int indexitem0 = 0;
                for (Object item0 : (java.util.Collection<?>) value(input, "exceptionCaseIds")) {
                    if (indexitem0 > 0) sql.append(",");
                    parameters.addValue("p1_" + indexitem0, parameter(item0));
                    sql.append(":p1_").append(indexitem0);
                    sql.append(" ");
                    indexitem0++;
                }
                sql.append(")");
                sql.append("""
                    ORDER BY ea.exception_case_id, ea.action_seq
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.exceptioncase.dto.ExceptionActionRow.class), "", "");
            }
            case "countOpenByType" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT exception_type AS exceptionType,
                           COUNT(*)       AS count
                      FROM fgc.exception_case
                     WHERE status IN ('NEW', 'IN_REVIEW')
                     GROUP BY exception_type
                     ORDER BY exception_type
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.exceptioncase.dto.ExceptionTypeSummaryRow.class), "", "");
            }
            case "findReasonCodes" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT DISTINCT reason_code
                      FROM fgc.exception_case
                     WHERE reason_code IS NOT NULL
                     ORDER BY reason_code
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(String.class), "", "");
            }
            case "findAssignees" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT DISTINCT ec.assigned_to AS userId,
                           au.login_id             AS loginId
                      FROM fgc.exception_case ec
                      JOIN fgc.app_user au ON au.user_id = ec.assigned_to
                     ORDER BY au.login_id
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.exceptioncase.dto.ExceptionAssigneeRow.class), "", "");
            }
            case "findValidationMonths" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT DISTINCT validation_month
                      FROM fgc.exception_case
                     WHERE validation_month IS NOT NULL
                     ORDER BY validation_month DESC
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(java.time.LocalDate.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
