package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.reconciliation.dto.InsurerGaActualSourceRow;
import com.susukkang.fgc.reconciliation.dto.InsurerGaExpectedSourceRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 보험사→GA 예상 스케줄과 확정 실제 명세·수기 지급 원천 조회.
 * PostgreSQL 원천·집계·잠금 계약을 보존하며 타입 지정 projection으로 조회한다.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class InsurerGaReconciliationRepository {
    private final EntityManager entityManager;
    private final ReconciliationExpectedSourceQuery expectedSources;

    public List<InsurerGaExpectedSourceRow> findExpectedSources(LocalDate settlementMonth, Long insurerId) {
        return expectedSources.find(settlementMonth, insurerId,
                com.susukkang.fgc.common.code.PaymentStage.INSURER_TO_GA, InsurerGaExpectedSourceRow::new);
    }

    public List<InsurerGaActualSourceRow> findActualSources(LocalDate settlementMonth, Long insurerId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
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
                AND sb.insurer_id = :insurerId
                AND sb.settlement_month = :settlementMonth
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
                WHERE ct.insurer_id = :insurerId
                AND (
                (ct.source_type = 'INSURER_STATEMENT'
                AND sb.statement_batch_id IS NOT NULL
                AND sb.statement_type = 'INSURER_COMMISSION'
                AND sb.status IN ('AVAILABLE', 'VALIDATED', 'RECONCILED'))
                OR (ct.source_type = 'GA_MANUAL_PAYMENT'
                AND ct.statement_batch_id IS NULL)
                )
                AND ct.settlement_month = :settlementMonth
                AND ct.payment_stage = 'INSURER_TO_GA'
                AND ct.source_type IN ( 'INSURER_STATEMENT','GA_MANUAL_PAYMENT')
                AND ct.cashflow_type = 'PAYMENT'
                AND ct.status = 'CONFIRMED'
                AND ta.attribution_scope = 'CONTRACT'
                ORDER BY ta.contract_id, ct.commission_item_id,
                COALESCE(ct.due_date, ct.settlement_month),
                ct.commission_transaction_id, ta.attribution_seq
                """).unwrap(NativeQuery.class);
        query.setParameter("settlementMonth", settlementMonth);
        query.setParameter("insurerId", insurerId);
        query.addScalar("transactionattributionid", Long.class);
        query.addScalar("commissiontransactionid", Long.class);
        query.addScalar("journalheaderid", Long.class);
        query.addScalar("contractid", Long.class);
        query.addScalar("actualagentid", Long.class);
        query.addScalar("actualagentmappingcount", Integer.class);
        query.addScalar("commissionitemid", Long.class);
        query.addScalar("actualinstallmentno", Integer.class);
        query.addScalar("settlementmonth", LocalDate.class);
        query.addScalar("duedate", LocalDate.class);
        query.addScalar("actualamount", BigDecimal.class);
        query.addScalar("sourcebusinesskey", String.class);
        query.addScalar("sourceagentcode", String.class);
        return query.setTupleTransformer((values, aliases) -> {
            InsurerGaActualSourceRow row = new InsurerGaActualSourceRow();
            row.setTransactionAttributionId((Long) values[0]);
            row.setCommissionTransactionId((Long) values[1]);
            row.setJournalHeaderId((Long) values[2]);
            row.setContractId((Long) values[3]);
            row.setActualAgentId((Long) values[4]);
            row.setActualAgentMappingCount((Integer) values[5]);
            row.setCommissionItemId((Long) values[6]);
            row.setActualInstallmentNo((Integer) values[7]);
            row.setSettlementMonth((LocalDate) values[8]);
            row.setDueDate((LocalDate) values[9]);
            row.setActualAmount((BigDecimal) values[10]);
            row.setSourceBusinessKey((String) values[11]);
            row.setSourceAgentCode((String) values[12]);
            return row;
        }).getResultList();
    }
}
