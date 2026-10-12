package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class JournalImbalanceBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "findBalanceSummary" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "journalHeaderId")));
                sql.append("""
                    SELECT journal_header_id,
                           COALESCE(SUM(debit_amount), 0) AS debit_total,
                           COALESCE(SUM(credit_amount), 0) AS credit_total
                    FROM fgc.journal_line
                    WHERE journal_header_id = :p0
                    GROUP BY journal_header_id
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.journal.dto.JournalBalanceSummary.class), "", "");
            }
            case "findImbalances" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                sql.append("""
                    SELECT vw.journal_header_id, vw.journal_no, h.journal_type, vw.status,
                           vw.debit_total, vw.credit_total, vw.difference_amount
                    FROM fgc.vw_journal_imbalance vw
                    JOIN fgc.journal_header h ON h.journal_header_id = vw.journal_header_id
                    WHERE h.validation_run_id = :p0
                    ORDER BY vw.journal_header_id
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.journal.dto.LedgerImbalanceRow.class), "", "");
            }
            case "countImbalances" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                sql.append("""
                    SELECT COUNT(*)
                    FROM fgc.vw_journal_imbalance vw
                    JOIN fgc.journal_header h ON h.journal_header_id = vw.journal_header_id
                    WHERE h.validation_run_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            case "countJournals" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "validationRunId")));
                sql.append("""
                    SELECT COUNT(*)
                    FROM fgc.journal_header
                    WHERE validation_run_id = :p0
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
