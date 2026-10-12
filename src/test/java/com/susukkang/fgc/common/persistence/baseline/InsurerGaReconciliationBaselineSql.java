package com.susukkang.fgc.common.persistence.baseline;

import static com.susukkang.fgc.common.persistence.baseline.JdbcBaseline.*;

/** 전환 전 SQL 비교 기준. 운영 저장소와 공유하지 않는 JDBC 테스트 픽스처. */
final class InsurerGaReconciliationBaselineSql {
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
                           c.agent_id AS expectedAgentId,
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
                      JOIN fgc.journal_header jh
                        ON jh.journal_type = 'EXPECTED_INSURER_INCOME'
                       AND jh.source_entity_type = 'SCHEDULE_LINE'
                       AND jh.source_entity_id = CAST(sl.schedule_line_id AS varchar)
                       AND jh.status = 'POSTED'
                     WHERE sh.payment_stage = 'INSURER_TO_GA'
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
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.InsurerGaExpectedSourceRow.class), "", "");
            }
            case "findActualSources" -> {
                StringBuilder sql = new StringBuilder();
                var parameters = new org.springframework.jdbc.core.namedparam.MapSqlParameterSource();
                parameters.addValue("p0", parameter(value(input, "insurerId")));
                parameters.addValue("p1", parameter(value(input, "settlementMonth")));
                parameters.addValue("p2", parameter(value(input, "insurerId")));
                parameters.addValue("p3", parameter(value(input, "settlementMonth")));
                sql.append("""
                    SELECT ta.transaction_attribution_id AS transactionAttributionId,
                           ct.commission_transaction_id AS commissionTransactionId,
                           jh.journal_header_id AS journalHeaderId,
                           ta.contract_id AS contractId,
                           CASE
                               WHEN ta.source_agent_code IS NOT NULL THEN aic.agent_id
                               ELSE ta.agent_id
                           END AS actualAgentId,
                           CASE
                               WHEN ta.source_agent_code IS NOT NULL THEN aic.mapping_count
                               WHEN ta.agent_id IS NOT NULL THEN 1
                               ELSE 0
                           END AS actualAgentMappingCount,
                           ct.commission_item_id AS commissionItemId,
                           ct.installment_no AS actualInstallmentNo,
                           ct.settlement_month AS settlementMonth,
                           ct.due_date AS dueDate,
                           ta.attributed_amount AS actualAmount,
                           ct.source_business_key AS sourceBusinessKey,
                           ta.source_agent_code AS sourceAgentCode
                      FROM fgc.commission_transaction ct
                      LEFT JOIN fgc.statement_batch sb
                        ON sb.statement_batch_id = ct.statement_batch_id
                       AND sb.insurer_id = :p0
                       AND sb.settlement_month = :p1
                      JOIN fgc.transaction_attribution ta
                        ON ta.commission_transaction_id = ct.commission_transaction_id
                      JOIN fgc.journal_header jh
                        ON jh.journal_type = 'ACTUAL_INSURER_STATEMENT'
                       AND jh.source_entity_type = 'COMMISSION_TRANSACTION'
                        AND jh.source_entity_id = CAST(ct.commission_transaction_id AS varchar)
                        AND jh.status = 'POSTED'
                      LEFT JOIN LATERAL (
                          SELECT CASE
                                     WHEN COUNT(DISTINCT code.agent_id) = 1 THEN MIN(code.agent_id)
                                 END AS agent_id,
                                 COUNT(*)::integer AS mapping_count
                            FROM fgc.agent_insurer_code code
                           WHERE code.insurer_id = ct.insurer_id
                             AND code.insurer_agent_code = ta.source_agent_code
                             AND code.code_status IN ('ACTIVE', 'REPRESENTATIVE')
                             AND code.effective_from <= ta.attribution_date
                             AND (code.effective_to IS NULL OR code.effective_to >= ta.attribution_date)
                      ) aic ON TRUE
                     WHERE ct.insurer_id = :p2
                       -- 2026-08-19 hjKang - 수기 등록 원수사 수입 건을 대사 대상에 포함
                       -- 기존 코드: statement_batch 를 INNER JOIN 해 명세 묶음이 있는 건만 조회했다.
                       -- 문제: TRAN-W02 로 등록한 INSURER_TO_GA 지급 건은 statement_batch_id 가 NULL 이라
                       --       source_type 조건을 풀어도 조인에서 전부 탈락했다.
                       -- 개선: 묶음을 LEFT JOIN 으로 바꾸고 원천유형별로 요구조건을 나눈다.
                       --       정규화 적재분(INSURER_STATEMENT)은 유효한 명세 묶음을 여전히 필수로 요구하고,
                       --       수기 등록분(GA_MANUAL_PAYMENT)은 묶음 없이 허용한다.
                       AND (
                             (ct.source_type = 'INSURER_STATEMENT'
                              AND sb.statement_batch_id IS NOT NULL
                              AND sb.statement_type = 'INSURER_COMMISSION'
                              AND sb.status IN ('AVAILABLE', 'VALIDATED', 'RECONCILED'))
                          OR (ct.source_type = 'GA_MANUAL_PAYMENT'
                              AND ct.statement_batch_id IS NULL)
                           )
                       AND ct.settlement_month = :p3
                       AND ct.payment_stage = 'INSURER_TO_GA'
                       AND ct.source_type IN ( 'INSURER_STATEMENT','GA_MANUAL_PAYMENT')
                       AND ct.cashflow_type = 'PAYMENT'
                       AND ct.status = 'CONFIRMED'
                       AND ta.attribution_scope = 'CONTRACT'
                     ORDER BY ta.contract_id, ct.commission_item_id,
                              COALESCE(ct.due_date, ct.settlement_month),
                              ct.commission_transaction_id, ta.attribution_seq
                    """);
                sql.append(" ");
                yield new Statement(sql.toString(), parameters, rows(com.susukkang.fgc.reconciliation.dto.InsurerGaActualSourceRow.class), "", "");
            }
            default -> throw new IllegalArgumentException("Unknown baseline statement: " + method);
        };
    }
}
