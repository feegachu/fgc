package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class ValidationRunReadBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "findById" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                sql.append("""
                    SELECT validation_run_id AS validationRunId,
                           validation_month  AS validationMonth,
                           run_no            AS runNo,
                           run_type          AS runType,
                           status,
                           current_step      AS currentStep,
                           started_at        AS startedAt,
                           completed_at      AS completedAt,
                           finalized_at      AS finalizedAt,
                           triggered_by      AS triggeredBy,
                           finalized_by      AS finalizedBy,
                           failure_message   AS failureMessage,
                           created_at        AS createdAt
                      FROM fgc.validation_run
                     WHERE validation_run_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.validation.dto.ValidationRunRow.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
