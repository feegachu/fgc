package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class CapScheduleAmountBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "findFirstYearScheduleAmounts" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                parameters.addValue("p2", parameter(value(input, "firstYearMonths")));
                sql.append("""
                    SELECT sl.schedule_line_id      AS scheduleLineId,
                           sl.commission_item_id     AS commissionItemId,
                           sl.contract_month_no      AS contractMonthNo,
                           sl.expected_amount        AS amount,
                           (
                               SELECT CASE
                                          WHEN COUNT(ta.transaction_attribution_id) > 0
                                           AND BOOL_AND(NULLIF(BTRIM(ta.evidence_ref), '') IS NOT NULL)
                                              THEN MIN(NULLIF(BTRIM(ta.evidence_ref), ''))
                                      END
                                 FROM fgc.transaction_attribution ta
                                 JOIN fgc.commission_transaction ct
                                   ON ct.commission_transaction_id = ta.commission_transaction_id
                                WHERE ta.schedule_line_id = sl.schedule_line_id
                                  AND ct.status = 'CONFIRMED'
                                  AND ct.payment_stage = sh.payment_stage
                                  AND ct.commission_item_id = sl.commission_item_id
                                  AND ta.inclusion_status_snapshot = 'EXCLUDED'
                           ) AS evidenceRef
                      FROM fgc.schedule_line sl
                      JOIN fgc.schedule_header sh ON sh.schedule_header_id = sl.schedule_header_id
                     WHERE sh.contract_id = :p0
                       AND sh.payment_stage = :p1
                       AND sh.schedule_purpose = 'OPERATIONAL'
                       AND sh.active_yn = true
                       AND sl.line_status <> 'CANCELLED'
                       AND sl.contract_month_no BETWEEN 1 AND :p2
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.ScheduleAmountView.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
