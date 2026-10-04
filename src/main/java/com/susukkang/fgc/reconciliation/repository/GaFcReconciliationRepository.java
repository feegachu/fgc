package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.reconciliation.dto.GaFcActualSourceRow;
import com.susukkang.fgc.reconciliation.dto.GaFcExpectedSourceRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : GA→FC 운영 스케줄과 확정 지급의 계약 귀속 원천 조회.
 * PostgreSQL 원천·집계·잠금 계약을 보존하며 타입 지정 projection으로 조회한다.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GaFcReconciliationRepository {
    private final EntityManager entityManager;

    public List<GaFcExpectedSourceRow> findExpectedSources(LocalDate settlementMonth, Long insurerId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
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
                AND sl.due_month = :settlementMonth
                AND c.insurer_id = :insurerId
                ORDER BY sh.contract_id, sl.commission_item_id, sl.due_month,
                sl.installment_no, sl.schedule_line_id
                """).unwrap(NativeQuery.class);
        query.setParameter("settlementMonth", settlementMonth);
        query.setParameter("insurerId", insurerId);
        query.addScalar("schedulelineid", Long.class);
        query.addScalar("journalheaderid", Long.class);
        query.addScalar("contractid", Long.class);
        query.addScalar("expectedagentid", Long.class);
        query.addScalar("commissionitemid", Long.class);
        query.addScalar("installmentno", Integer.class);
        query.addScalar("duedate", LocalDate.class);
        query.addScalar("duemonth", LocalDate.class);
        query.addScalar("expectedamount", BigDecimal.class);
        return query.setTupleTransformer((values, aliases) -> {
            GaFcExpectedSourceRow row = new GaFcExpectedSourceRow();
            row.setScheduleLineId((Long) values[0]);
            row.setJournalHeaderId((Long) values[1]);
            row.setContractId((Long) values[2]);
            row.setExpectedAgentId((Long) values[3]);
            row.setCommissionItemId((Long) values[4]);
            row.setInstallmentNo((Integer) values[5]);
            row.setDueDate((LocalDate) values[6]);
            row.setDueMonth((LocalDate) values[7]);
            row.setExpectedAmount((BigDecimal) values[8]);
            return row;
        }).getResultList();
    }

    public List<GaFcActualSourceRow> findActualSources(LocalDate settlementMonth, Long insurerId) {
        NativeQuery<?> query = entityManager.createNativeQuery("""
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
                AND ct.settlement_month = :settlementMonth
                AND ct.cashflow_type = 'PAYMENT'
                AND ct.status = 'CONFIRMED'
                AND ta.attribution_scope = 'CONTRACT'
                AND ta.attribution_month = :settlementMonth
                AND c.insurer_id = :insurerId
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
        query.addScalar("commissionitemid", Long.class);
        query.addScalar("actualinstallmentno", Integer.class);
        query.addScalar("settlementmonth", LocalDate.class);
        query.addScalar("duedate", LocalDate.class);
        query.addScalar("actualamount", BigDecimal.class);
        query.addScalar("sourcebusinesskey", String.class);
        return query.setTupleTransformer((values, aliases) -> {
            GaFcActualSourceRow row = new GaFcActualSourceRow();
            row.setTransactionAttributionId((Long) values[0]);
            row.setCommissionTransactionId((Long) values[1]);
            row.setJournalHeaderId((Long) values[2]);
            row.setContractId((Long) values[3]);
            row.setActualAgentId((Long) values[4]);
            row.setCommissionItemId((Long) values[5]);
            row.setActualInstallmentNo((Integer) values[6]);
            row.setSettlementMonth((LocalDate) values[7]);
            row.setDueDate((LocalDate) values[8]);
            row.setActualAmount((BigDecimal) values[9]);
            row.setSourceBusinessKey((String) values[10]);
            return row;
        }).getResultList();
    }
}
