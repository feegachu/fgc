package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class CorrectionActionBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "findByIdForUpdate" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "exceptionCaseId")));
                sql.append("""
                    SELECT exception_case_id AS exceptionCaseId,
                           exception_type    AS exceptionType,
                           status,
                           assigned_to       AS assignedTo
                      FROM fgc.exception_case
                     WHERE exception_case_id = :p0
                       FOR UPDATE
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.exceptioncase.service.CorrectionActionBaseline.Target.class), "", "");
            }
            case "findJournalCorrectionTargetForUpdate" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "exceptionCaseId")));
                sql.append("""
                    SELECT exception_case_id  AS exceptionCaseId,
                           exception_type     AS exceptionType,
                           status,
                           source_entity_type AS sourceEntityType,
                           source_entity_id   AS sourceEntityId
                      FROM fgc.exception_case
                     WHERE exception_case_id = :p0
                       FOR UPDATE
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, mapRows(), "", "");
            }
            case "findNextActionSeq" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "exceptionCaseId")));
                sql.append("""
                    SELECT COALESCE(MAX(action_seq), 0) + 1
                      FROM fgc.exception_action
                     WHERE exception_case_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Integer.class), "", "");
            }
            case "insertAction" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "exceptionCaseId")));
                parameters.addValue("p1", parameter(value(input, "actionSeq")));
                parameters.addValue("p2", parameter(value(input, "fromStatus")));
                parameters.addValue("p3", parameter(value(input, "toStatus")));
                parameters.addValue("p4", parameter(value(input, "actionType")));
                parameters.addValue("p5", parameter(value(input, "reason")));
                parameters.addValue("p6", parameter(value(input, "evidenceRef")));
                parameters.addValue("p7", parameter(value(input, "actionBy")));
                parameters.addValue("p8", parameter(value(input, "actionAt")));
                sql.append("""
                    INSERT INTO fgc.exception_action (
                        exception_case_id, action_seq, from_status, to_status,
                        action_type, reason, evidence_ref, action_by, action_at
                    ) VALUES (
                        :p0, :p1, :p2, :p3,
                        :p4, :p5, :p6, :p7, :p8
                    )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "updateCaseAfterAction" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "status")));
                parameters.addValue("p1", parameter(value(input, "assignedTo")));
                parameters.addValue("p2", parameter(value(input, "resolvedAt")));
                parameters.addValue("p3", parameter(value(input, "exceptionCaseId")));
                sql.append("""
                    UPDATE fgc.exception_case
                       SET status = :p0,
                           assigned_to = :p1,
                           resolved_at = :p2
                     WHERE exception_case_id = :p3
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
