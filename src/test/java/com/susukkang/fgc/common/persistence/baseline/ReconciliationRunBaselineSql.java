package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class ReconciliationRunBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "existsActiveInsurer" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "insurerId")));
                sql.append("""
                    SELECT EXISTS (
                        SELECT 1
                          FROM fgc.insurer
                         WHERE insurer_id = :p0
                           AND active_yn = TRUE
                    )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Boolean.class), "", "");
            }
            case "insert" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                parameters.addValue("p1", parameter(value(input, "settlementMonth")));
                parameters.addValue("p2", parameter(value(input, "paymentStage")));
                parameters.addValue("p3", parameter(value(input, "insurerId")));
                parameters.addValue("p4", parameter(value(input, "createdBy")));
                sql.append("""
                    INSERT INTO fgc.reconciliation_run (
                        validation_run_id,
                        settlement_month,
                        payment_stage,
                        insurer_id,
                        created_by
                    ) VALUES (
                        :p0,
                        :p1,
                        :p2,
                        :p3,
                        :p4
                    )
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "reconciliationRunId", "reconciliation_run_id");
            }
            case "transitionToRunning" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "reconciliationRunId")));
                sql.append("""
                    UPDATE fgc.reconciliation_run
                       SET status = 'RUNNING',
                           started_at = clock_timestamp(),
                           completed_at = NULL
                     WHERE reconciliation_run_id = :p0
                       AND status IN ('CREATED', 'FAILED')
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "transitionToCompleted" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "reconciliationRunId")));
                sql.append("""
                    UPDATE fgc.reconciliation_run
                       SET status = 'COMPLETED',
                           completed_at = clock_timestamp()
                     WHERE reconciliation_run_id = :p0
                       AND status = 'RUNNING'
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "transitionToFailed" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "reconciliationRunId")));
                sql.append("""
                    UPDATE fgc.reconciliation_run
                       SET status = 'FAILED',
                           completed_at = clock_timestamp()
                     WHERE reconciliation_run_id = :p0
                       AND status = 'RUNNING'
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(Object.class), "", "");
            }
            case "findById" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "reconciliationRunId")));
                sql.append("""
                    SELECT reconciliation_run_id AS reconciliationRunId,
                           validation_run_id AS validationRunId,
                           settlement_month AS settlementMonth,
                           payment_stage AS paymentStage,
                           insurer_id AS insurerId,
                           status,
                           started_at AS startedAt,
                           completed_at AS completedAt,
                           created_by AS createdBy,
                           created_at AS createdAt
                      FROM fgc.reconciliation_run
                     WHERE reconciliation_run_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow.class), "", "");
            }
            case "findByNaturalKey" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "settlementMonth")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                parameters.addValue("p2", parameter(value(input, "insurerId")));
                parameters.addValue("p3", parameter(value(input, "validationRunId")));
                sql.append("""
                    SELECT reconciliation_run_id AS reconciliationRunId,
                           validation_run_id AS validationRunId,
                           settlement_month AS settlementMonth,
                           payment_stage AS paymentStage,
                           insurer_id AS insurerId,
                           status,
                           started_at AS startedAt,
                           completed_at AS completedAt,
                           created_by AS createdBy,
                           created_at AS createdAt
                      FROM fgc.reconciliation_run
                     WHERE settlement_month = :p0
                       AND payment_stage = :p1
                       AND insurer_id = :p2
                       AND validation_run_id = :p3
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow.class), "", "");
            }
            case "findSelectedInsurerIds" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                sql.append("""
                    SELECT DISTINCT contract.insurer_id
                      FROM fgc.validation_target target
                      JOIN fgc.insurance_contract contract
                        ON contract.contract_id = target.contract_id
                      JOIN fgc.insurer insurer
                        ON insurer.insurer_id = contract.insurer_id
                     WHERE target.validation_run_id = :p0
                       AND target.selection_status = 'SELECTED'
                       AND insurer.active_yn = TRUE
                     ORDER BY contract.insurer_id
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "findSelectedContractIds" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                parameters.addValue("p1", parameter(value(input, "insurerId")));
                sql.append("""
                    SELECT target.contract_id
                      FROM fgc.validation_target target
                      JOIN fgc.insurance_contract contract
                        ON contract.contract_id = target.contract_id
                      JOIN fgc.insurer insurer
                        ON insurer.insurer_id = contract.insurer_id
                     WHERE target.validation_run_id = :p0
                       AND target.selection_status = 'SELECTED'
                       AND contract.insurer_id = :p1
                       AND insurer.active_yn = TRUE
                     ORDER BY target.contract_id
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "lockValidationRun" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                sql.append("""
                    SELECT validation_run_id
                      FROM fgc.validation_run
                     WHERE validation_run_id = :p0
                       FOR UPDATE
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
