package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class CapIncludedAmountBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "sumIncludedAmountByContractAndAgent" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                parameters.addValue("p2", parameter(value(input, "transactionId")));
                sql.append("""
                    SELECT
                        ta.contract_id,
                        ta.agent_id,
                        ct.payment_stage,
                        COALESCE(
                            SUM(ROUND(ta.attributed_amount, 0)),
                            0
                        ) AS included_amount
                    FROM
                        transaction_attribution ta
                    JOIN
                        commission_transaction ct
                    ON
                        ct.commission_transaction_id = ta.commission_transaction_id
                    JOIN
                        insurance_contract c
                    ON
                        c.contract_id = ta.contract_id
                    WHERE
                        ta.contract_id = :p0
                    AND
                        ct.payment_stage = :p1 AND ct.cashflow_type = 'PAYMENT'
                    AND
                        ta.inclusion_status_snapshot = 'INCLUDED'
                    AND
                        ta.attribution_date >= c.contract_date
                    AND
                        ta.attribution_date  <  c.contract_date + INTERVAL '1 year'
                    AND
                        (ct.status = 'CONFIRMED' OR ct.commission_transaction_id = :p2)
                    GROUP BY
                        ta.contract_id,
                        ta.agent_id,
                        ct.payment_stage
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapIncludedAmountSummary.class), "", "");
            }
            case "sumConfirmedIncludedAmountByContractAndAgent" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "contractId")));
                parameters.addValue("p1", parameter(value(input, "paymentStage")));
                sql.append("""
                    SELECT
                        ta.contract_id,
                        ta.agent_id,
                        ct.payment_stage,
                        COALESCE(
                            SUM(ROUND(ta.attributed_amount, 0)),
                            0
                        ) AS included_amount
                    FROM
                        transaction_attribution ta
                    JOIN
                        commission_transaction ct
                    ON
                        ct.commission_transaction_id = ta.commission_transaction_id
                    JOIN
                        insurance_contract c
                    ON
                        c.contract_id = ta.contract_id
                    WHERE
                        ta.contract_id = :p0
                    AND
                        ct.payment_stage = :p1 AND ct.cashflow_type = 'PAYMENT'
                    AND
                        ta.inclusion_status_snapshot = 'INCLUDED'
                    AND
                        ta.attribution_date >= c.contract_date
                    AND
                        ta.attribution_date  <  c.contract_date + INTERVAL '1 year'
                    AND
                        ct.status = 'CONFIRMED'
                    GROUP BY
                        ta.contract_id,
                        ta.agent_id,
                        ct.payment_stage
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.cap.dto.CapIncludedAmountSummary.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
