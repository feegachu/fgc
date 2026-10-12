package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class RefundRateBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "findApplicableTable" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "insurerId")));
                parameters.addValue("p1", parameter(value(input, "productId")));
                parameters.addValue("p2", parameter(value(input, "paymentTermMonths")));
                parameters.addValue("p3", parameter(value(input, "channelCode")));
                parameters.addValue("p4", parameter(value(input, "asOfDate")));
                parameters.addValue("p5", parameter(value(input, "asOfDate")));
                sql.append("""
                    SELECT rrt.refund_rate_table_id    AS refundRateTableId,
                           rrt.policy_version_id        AS policyVersionId,
                           pv.version_no                AS versionNo,
                           rrt.standard_deduction_80_yn AS standardDeduction80Yn,
                           rrt.effective_from           AS effectiveFrom
                      FROM fgc.refund_rate_table rrt
                      JOIN fgc.policy_version pv ON pv.policy_version_id = rrt.policy_version_id
                     WHERE pv.status = 'ACTIVE'
                       AND rrt.insurer_id = :p0
                       AND rrt.product_id = :p1
                       AND rrt.payment_term_months = :p2
                       AND rrt.channel_code = :p3
                       AND rrt.effective_from <= :p4
                       AND (rrt.effective_to IS NULL OR rrt.effective_to >= :p5)
                     ORDER BY rrt.effective_from DESC
                     LIMIT 1
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.RefundRateTableView.class), "", "");
            }
            case "findRateAtMonth" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "refundRateTableId")));
                parameters.addValue("p1", parameter(value(input, "contractMonthNo")));
                sql.append("""
                    SELECT refund_rate_pct
                      FROM fgc.refund_rate_line
                     WHERE refund_rate_table_id = :p0
                       AND contract_month_no = :p1
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, scalar(java.math.BigDecimal.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
