package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class JournalSearchBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "search" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT h.journal_header_id, h.journal_no, h.journal_date, h.journal_type,
                           h.source_entity_type, h.source_entity_id, h.contract_id,
                           c.contract_no AS contractNo, h.status,
                           (SELECT COALESCE(SUM(l.debit_amount), 0) FROM fgc.journal_line l
                             WHERE l.journal_header_id = h.journal_header_id) AS debitTotal,
                           (SELECT COALESCE(SUM(l.credit_amount), 0) FROM fgc.journal_line l
                             WHERE l.journal_header_id = h.journal_header_id) AS creditTotal,
                           h.reversal_of_id AS reversalOfId, rev.journal_no AS reversalOfJournalNo,
                           creator.login_id AS createdBy, poster.login_id AS postedBy,
                           h.posted_at AS postedAt, h.created_at AS createdAt
                    FROM fgc.journal_header h
                    LEFT JOIN fgc.insurance_contract c ON c.contract_id = h.contract_id
                    LEFT JOIN fgc.journal_header rev ON rev.journal_header_id = h.reversal_of_id
                    LEFT JOIN fgc.app_user creator ON creator.user_id = h.created_by
                    LEFT JOIN fgc.app_user poster ON poster.user_id = h.posted_by
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "from") != null) {
                    parameters.addValue("p0", parameter(value(input, "from")));
                    sql.append("""
                        AND h.journal_date >= :p0
                        """);
                    sql.append(" ");
                }
                if (value(input, "to") != null) {
                    parameters.addValue("p1", parameter(value(input, "to")));
                    sql.append("""
                        AND h.journal_date <= :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "journalType") != null) {
                    parameters.addValue("p2", parameter(value(input, "journalType")));
                    sql.append("""
                        AND h.journal_type = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "contractId") != null) {
                    parameters.addValue("p3", parameter(value(input, "contractId")));
                    sql.append("""
                        AND h.contract_id = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "status") != null) {
                    parameters.addValue("p4", parameter(value(input, "status")));
                    sql.append("""
                        AND h.status = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "accountCode") != null) {
                    parameters.addValue("p5", parameter(value(input, "accountCode")));
                    sql.append("""
                        AND EXISTS (
                            SELECT 1 FROM fgc.journal_line l2
                            JOIN fgc.journal_account a2 ON a2.journal_account_id = l2.journal_account_id
                            WHERE l2.journal_header_id = h.journal_header_id
                            AND a2.account_code = :p5
                        )
                        """);
                    sql.append(" ");
                }
                parameters.addValue("p6", parameter(value(input, "limit")));
                parameters.addValue("p7", parameter(value(input, "offset")));
                sql.append("""
                    ORDER BY h.journal_date DESC, h.journal_header_id DESC
                    LIMIT :p6 OFFSET :p7
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.journal.dto.JournalListRow.class), "", "");
            }
            case "count" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT COUNT(*)
                    FROM fgc.journal_header h
                    """);
                sql.append(" ");
                sql.append(" WHERE 1=1 ");
                if (value(input, "from") != null) {
                    parameters.addValue("p0", parameter(value(input, "from")));
                    sql.append("""
                        AND h.journal_date >= :p0
                        """);
                    sql.append(" ");
                }
                if (value(input, "to") != null) {
                    parameters.addValue("p1", parameter(value(input, "to")));
                    sql.append("""
                        AND h.journal_date <= :p1
                        """);
                    sql.append(" ");
                }
                if (value(input, "journalType") != null) {
                    parameters.addValue("p2", parameter(value(input, "journalType")));
                    sql.append("""
                        AND h.journal_type = :p2
                        """);
                    sql.append(" ");
                }
                if (value(input, "contractId") != null) {
                    parameters.addValue("p3", parameter(value(input, "contractId")));
                    sql.append("""
                        AND h.contract_id = :p3
                        """);
                    sql.append(" ");
                }
                if (value(input, "status") != null) {
                    parameters.addValue("p4", parameter(value(input, "status")));
                    sql.append("""
                        AND h.status = :p4
                        """);
                    sql.append(" ");
                }
                if (value(input, "accountCode") != null) {
                    parameters.addValue("p5", parameter(value(input, "accountCode")));
                    sql.append("""
                        AND EXISTS (
                            SELECT 1 FROM fgc.journal_line l2
                            JOIN fgc.journal_account a2 ON a2.journal_account_id = l2.journal_account_id
                            WHERE l2.journal_header_id = h.journal_header_id
                            AND a2.account_code = :p5
                        )
                        """);
                    sql.append(" ");
                }
                yield new Statement(sql.toString(), parameters, scalar(Long.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
