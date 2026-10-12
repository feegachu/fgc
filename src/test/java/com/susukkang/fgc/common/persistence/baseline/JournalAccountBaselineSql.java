package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class JournalAccountBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "findActiveByCode" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "accountCode")));
                sql.append("""
                    SELECT journal_account_id, account_code, account_name, normal_balance, active_yn
                    FROM fgc.journal_account
                    WHERE account_code = :p0 AND active_yn = true
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.journal.dto.JournalAccountRow.class), "", "");
            }
            case "findAllActive" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                sql.append("""
                    SELECT journal_account_id, account_code, account_name, normal_balance, active_yn
                    FROM fgc.journal_account
                    WHERE active_yn = true
                    ORDER BY account_code
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.journal.dto.JournalAccountRow.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
