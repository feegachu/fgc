package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class ReconciliationRunHistoryBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "search" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT rr.reconciliation_run_id, rr.validation_run_id, rr.settlement_month, rr.payment_stage,
                           rr.insurer_id, ins.insurer_name AS insurerName,
                           rr.status, rr.tolerance_policy_version_id,
                           creator.login_id AS createdBy, rr.created_at AS createdAt,
                           rr.started_at AS startedAt, rr.completed_at AS completedAt,
                           rr.finalized_at AS finalizedAt, finalizer.login_id AS finalizedBy,
                           vw.result_count AS targetCount, vw.matched_count AS matchedCount,
                           vw.exception_count AS exceptionCount,
                           vw.expected_total AS expectedTotal, vw.actual_total AS actualTotal,
                           vw.difference_total AS differenceTotal
                    FROM fgc.reconciliation_run rr
                    JOIN fgc.vw_reconciliation_summary vw ON vw.reconciliation_run_id = rr.reconciliation_run_id
                    LEFT JOIN fgc.insurer ins ON ins.insurer_id = rr.insurer_id
                    LEFT JOIN fgc.app_user creator ON creator.user_id = rr.created_by
                    LEFT JOIN fgc.app_user finalizer ON finalizer.user_id = rr.finalized_by
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "settlementMonth") != null) {
                    parameters.addValue("p0", parameter(value(input, "settlementMonth")));
                    sql.append("""
                        AND rr.settlement_month = :p0
                        """);
                    sql.append(" ");
                }
                if (value(input, "paymentStage") != null) {
                    parameters.addValue("p1", parameter(value(input, "paymentStage")));
                    sql.append("""
                        AND rr.payment_stage = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "insurerId") != null) {
                    parameters.addValue("p2", parameter(value(input, "insurerId")));
                    sql.append("""
                        AND rr.insurer_id = :p2
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    ORDER BY rr.created_at
                    """);
                sql.append(" ");
                if ("asc".equals(value(input, "sortDirection"))) {
                    sql.append("""
                        ASC
                        """);
                    sql.append(" ");
                }
                else {
                    sql.append("""
                        DESC
                        """);
                    sql.append(" ");
                }
                sql.append("""
                    ,
                            rr.reconciliation_run_id
                    """);
                sql.append(" ");
                if ("asc".equals(value(input, "sortDirection"))) {
                    sql.append("""
                        ASC
                        """);
                    sql.append(" ");
                }
                else {
                    sql.append("""
                        DESC
                        """);
                    sql.append(" ");
                }
                parameters.addValue("p3", parameter(value(input, "limit")));
                parameters.addValue("p4", parameter(value(input, "offset")));
                sql.append("""
                    LIMIT :p3 OFFSET :p4
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.ReconciliationRunHistoryRow.class), "", "");
            }
            case "count" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT COUNT(*)
                    FROM fgc.reconciliation_run rr
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "settlementMonth") != null) {
                    parameters.addValue("p0", parameter(value(input, "settlementMonth")));
                    sql.append("""
                        AND rr.settlement_month = :p0
                        """);
                    sql.append(" ");
                }
                if (value(input, "paymentStage") != null) {
                    parameters.addValue("p1", parameter(value(input, "paymentStage")));
                    sql.append("""
                        AND rr.payment_stage = :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "insurerId") != null) {
                    parameters.addValue("p2", parameter(value(input, "insurerId")));
                    sql.append("""
                        AND rr.insurer_id = :p2
                        """);
                    sql.append(" ");
                }
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
