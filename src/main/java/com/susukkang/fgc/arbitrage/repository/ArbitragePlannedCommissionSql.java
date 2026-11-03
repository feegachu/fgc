package com.susukkang.fgc.arbitrage.repository;

/** #754: 같은 지급 의무의 유효 확정 지급만 소비한다. 모든 원천은 한 SQL 문장의 같은 스냅샷에서 읽는다. */
final class ArbitragePlannedCommissionSql {
    private ArbitragePlannedCommissionSql() { }

    static final String SUMMARY = """
            WITH eligible_lines AS (
                SELECT sl.schedule_line_id, sl.commission_item_id, sl.beneficiary_agent_id,
                       sl.installment_no, sl.due_month, ROUND(sl.expected_amount, 0) AS expected_amount
                  FROM fgc.schedule_header sh
                  JOIN fgc.schedule_line sl ON sl.schedule_header_id = sh.schedule_header_id
                 WHERE sh.contract_id = :contractId AND sh.payment_stage = :paymentStage
                   AND sh.active_yn AND sh.schedule_purpose = 'OPERATIONAL'
                   AND sl.line_status IN ('PLANNED', 'HOLD')
                   AND NOT EXISTS (
                       SELECT 1 FROM fgc.contract_status_event e
                       WHERE e.contract_id = sh.contract_id
                         AND e.new_status IN ('TERMINATED', 'LAPSED', 'MATURED', 'CANCELLED')
                         AND (e.effective_at AT TIME ZONE 'Asia/Seoul')::date <= :asOfDate
                         AND sl.due_date > (e.effective_at AT TIME ZONE 'Asia/Seoul')::date
                         AND NOT EXISTS (
                             SELECT 1 FROM fgc.contract_status_event resumed
                              WHERE resumed.contract_id = e.contract_id
                                AND resumed.new_status IN ('ACTIVE', 'REVIVED')
                                AND (resumed.effective_at AT TIME ZONE 'Asia/Seoul')::date <= :asOfDate
                                AND (resumed.effective_at AT TIME ZONE 'Asia/Seoul')::date <= sl.due_date
                                AND (resumed.effective_at, resumed.event_seq, resumed.contract_status_event_id)
                                    > (e.effective_at, e.event_seq, e.contract_status_event_id)
                         )
                   )

            ), payments AS (
                SELECT ta.transaction_attribution_id, ta.schedule_line_id, ta.attribution_month,
                       ct.commission_item_id, ct.recipient_agent_id, ct.installment_no,
                       ROUND(ta.attributed_amount, 0) AS amount
                  FROM fgc.transaction_attribution ta
                  JOIN fgc.commission_transaction ct
                    ON ct.commission_transaction_id = ta.commission_transaction_id
                 WHERE ta.contract_id = :contractId AND ct.payment_stage = :paymentStage
                   AND ct.status = 'CONFIRMED' AND ct.cashflow_type = 'PAYMENT'
                   AND ta.attribution_date <= :asOfDate
            ), reconciled_pairs AS (
                SELECT MIN(m.schedule_line_id) AS schedule_line_id,
                       MIN(m.transaction_attribution_id) AS transaction_attribution_id
                  FROM fgc.reconciliation_match m
                  JOIN fgc.reconciliation_result result
                    ON result.reconciliation_result_id = m.reconciliation_result_id
                  JOIN fgc.reconciliation_run run
                    ON run.reconciliation_run_id = result.reconciliation_run_id
                 WHERE result.contract_id = :contractId AND run.payment_stage = :paymentStage
                   AND NOT run.superseded_yn AND run.status IN ('COMPLETED', 'FINALIZED')
                   AND result.result_type IN ('MATCHED', 'AMOUNT_DIFFERENCE')
                 GROUP BY result.reconciliation_result_id
                HAVING COUNT(DISTINCT m.schedule_line_id) = 1
                   AND COUNT(DISTINCT m.transaction_attribution_id) = 1
            ), evidence AS (
                SELECT p.transaction_attribution_id, sl.schedule_line_id
                  FROM payments p JOIN eligible_lines sl ON sl.schedule_line_id = p.schedule_line_id
                 WHERE sl.commission_item_id = p.commission_item_id
                   AND sl.beneficiary_agent_id IS NOT DISTINCT FROM p.recipient_agent_id
                   AND (p.installment_no IS NULL OR sl.installment_no = p.installment_no)
                UNION
                SELECT p.transaction_attribution_id, sl.schedule_line_id
                  FROM payments p
                  JOIN reconciled_pairs pair ON pair.transaction_attribution_id = p.transaction_attribution_id
                  JOIN eligible_lines sl ON sl.schedule_line_id = pair.schedule_line_id
                 WHERE sl.commission_item_id = p.commission_item_id
                   AND sl.beneficiary_agent_id IS NOT DISTINCT FROM p.recipient_agent_id
                   AND (p.installment_no IS NULL OR sl.installment_no = p.installment_no)
            ), resolved AS (
                SELECT transaction_attribution_id, MIN(schedule_line_id) AS schedule_line_id
                  FROM evidence GROUP BY transaction_attribution_id
                HAVING COUNT(*) = 1
            ), consumed AS (
                SELECT r.schedule_line_id, SUM(p.amount) AS amount
                  FROM resolved r JOIN payments p USING (transaction_attribution_id)
                 GROUP BY r.schedule_line_id
            ), unresolved AS (
                SELECT p.transaction_attribution_id
                  FROM payments p
                 WHERE NOT EXISTS (SELECT 1 FROM resolved r
                                   WHERE r.transaction_attribution_id = p.transaction_attribution_id)
                   AND EXISTS (
                       SELECT 1 FROM eligible_lines sl
                        WHERE sl.schedule_line_id = p.schedule_line_id
                           OR EXISTS (SELECT 1 FROM evidence e
                                      WHERE e.transaction_attribution_id = p.transaction_attribution_id)
                           OR (sl.commission_item_id = p.commission_item_id
                               AND (p.recipient_agent_id IS NULL
                                    OR sl.beneficiary_agent_id IS NOT DISTINCT FROM p.recipient_agent_id)
                               AND sl.due_month = p.attribution_month
                               AND (p.installment_no IS NULL OR sl.installment_no = p.installment_no)))
            )
            SELECT COALESCE((SELECT SUM(GREATEST(sl.expected_amount - COALESCE(c.amount, 0), 0))
                               FROM eligible_lines sl LEFT JOIN consumed c USING (schedule_line_id)), 0),
                   COALESCE((SELECT string_agg(CAST(transaction_attribution_id AS text), ','
                                              ORDER BY transaction_attribution_id) FROM unresolved), '')
            """;
}
