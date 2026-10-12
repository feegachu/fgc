package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class GaFcReconciliationBaselineSql {
    static Statement statement(String method, Object input) {
        return switch (method) {
            case "findExpectedSources" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "settlementMonth")));
                parameters.addValue("p1", parameter(value(input, "insurerId")));
                sql.append("""
                    SELECT sl.schedule_line_id AS scheduleLineId,
                           jh.journal_header_id AS journalHeaderId,
                           sh.contract_id AS contractId,
                           sl.beneficiary_agent_id AS expectedAgentId,
                           sl.commission_item_id AS commissionItemId,
                           sl.installment_no AS installmentNo,
                           sl.due_date AS dueDate,
                           sl.due_month AS dueMonth,
                           sl.expected_amount AS expectedAmount
                      FROM fgc.schedule_header sh
                      JOIN fgc.schedule_line sl
                        ON sl.schedule_header_id = sh.schedule_header_id
                      JOIN fgc.insurance_contract c
                        ON c.contract_id = sh.contract_id
                      LEFT JOIN fgc.journal_header jh
                        ON jh.journal_type = 'EXPECTED_FC_PAYOUT'
                       AND jh.source_entity_type = 'SCHEDULE_LINE'
                       AND jh.source_entity_id = CAST(sl.schedule_line_id AS varchar)
                       AND jh.status = 'POSTED'
                     WHERE sh.payment_stage = 'GA_TO_FC'
                       AND sh.schedule_purpose = 'OPERATIONAL'
                       AND sh.active_yn = TRUE
                       AND sh.status NOT IN ('HOLD', 'CANCELLED')
                       AND sl.line_status NOT IN ('HOLD', 'CANCELLED')
                       AND sl.due_month = :p0
                       AND c.insurer_id = :p1
                     ORDER BY sh.contract_id, sl.commission_item_id, sl.due_month,
                              sl.installment_no, sl.schedule_line_id
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.GaFcExpectedSourceRow.class), "", "");
            }
            case "findActualSources" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "settlementMonth")));
                parameters.addValue("p1", parameter(value(input, "settlementMonth")));
                parameters.addValue("p2", parameter(value(input, "insurerId")));
                sql.append("""
                    SELECT ta.transaction_attribution_id AS transactionAttributionId,
                           ct.commission_transaction_id AS commissionTransactionId,
                           jh.journal_header_id AS journalHeaderId,
                           ta.contract_id AS contractId,
                           ct.recipient_agent_id AS actualAgentId,
                           ct.commission_item_id AS commissionItemId,
                           ct.installment_no AS actualInstallmentNo,
                           ct.settlement_month AS settlementMonth,
                           ct.due_date AS dueDate,
                           ta.attributed_amount AS actualAmount,
                           ct.source_business_key AS sourceBusinessKey
                      FROM fgc.commission_transaction ct
                      JOIN fgc.transaction_attribution ta
                        ON ta.commission_transaction_id = ct.commission_transaction_id
                      JOIN fgc.insurance_contract c
                        ON c.contract_id = ta.contract_id
                      LEFT JOIN fgc.journal_header jh
                        ON jh.journal_type = 'CONFIRMED_FC_PAYOUT'
                       AND jh.source_entity_type = 'COMMISSION_TRANSACTION'
                       AND jh.source_entity_id = CAST(ct.commission_transaction_id AS varchar)
                       AND jh.status = 'POSTED'
                     WHERE ct.payment_stage = 'GA_TO_FC'
                       AND ct.settlement_month = :p0
                       AND ct.cashflow_type = 'PAYMENT'
                       AND ct.status = 'CONFIRMED'
                       AND ta.attribution_scope = 'CONTRACT'
                       AND ta.attribution_month = :p1
                       AND c.insurer_id = :p2
                     ORDER BY ta.contract_id, ct.commission_item_id,
                              COALESCE(ct.due_date, ct.settlement_month),
                              ct.commission_transaction_id, ta.attribution_seq
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.GaFcActualSourceRow.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
