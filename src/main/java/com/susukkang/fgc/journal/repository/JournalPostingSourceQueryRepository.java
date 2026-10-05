package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.journal.dto.ScheduleJournalSourceRow;
import com.susukkang.fgc.journal.dto.TransactionJournalSourceRow;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.hibernate.query.NativeQuery;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 검증 실행에 선별된 계약의 검증월 기표 원천을 조회한다.
 * 스케줄·지급·검증 대상 테이블은 아직 엔티티가 없어 네이티브 SQL로 DTO를 반환한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class JournalPostingSourceQueryRepository {

    private final EntityManager entityManager;

    public List<ScheduleJournalSourceRow> findExpectedInsurerIncomeSources(
            Long validationRunId, LocalDate validationMonth) {
        NativeQuery<?> query = createSourceQuery("""
                SELECT sl.schedule_line_id, sh.contract_id, sh.policy_version_id, sl.commission_item_id,
                       NULL AS beneficiary_agent_id, sl.due_date AS journal_date, sl.expected_amount AS amount,
                       '예상 수입 스케줄 ' || sl.installment_no || '회차' AS description
                  FROM fgc.schedule_line sl
                  JOIN fgc.schedule_header sh ON sh.schedule_header_id = sl.schedule_header_id
                  JOIN fgc.validation_target vt ON vt.contract_id = sh.contract_id
                 WHERE vt.validation_run_id = :validationRunId
                   AND vt.selection_status = 'SELECTED'
                   AND sh.payment_stage = 'INSURER_TO_GA'
                   AND sh.active_yn = true
                   AND sh.schedule_purpose = 'OPERATIONAL'
                   AND sh.status NOT IN ('HOLD', 'CANCELLED')
                   AND sl.due_month = :validationMonth
                   AND sl.line_status NOT IN ('HOLD', 'CANCELLED')
                 ORDER BY sl.schedule_line_id
                """, validationRunId, validationMonth, "schedule_line_id");
        return query.setTupleTransformer((row, aliases) -> toScheduleSource(row)).getResultList();
    }

    public List<ScheduleJournalSourceRow> findExpectedFcPayoutSources(
            Long validationRunId, LocalDate validationMonth) {
        NativeQuery<?> query = createSourceQuery("""
                SELECT sl.schedule_line_id, sh.contract_id, sh.policy_version_id, sl.commission_item_id,
                       sl.beneficiary_agent_id, sl.due_date AS journal_date, sl.expected_amount AS amount,
                       '예상 지급 스케줄 ' || sl.installment_no || '회차' AS description
                  FROM fgc.schedule_line sl
                  JOIN fgc.schedule_header sh ON sh.schedule_header_id = sl.schedule_header_id
                  JOIN fgc.validation_target vt ON vt.contract_id = sh.contract_id
                 WHERE vt.validation_run_id = :validationRunId
                   AND vt.selection_status = 'SELECTED'
                   AND sh.payment_stage = 'GA_TO_FC'
                   AND sh.active_yn = true
                   AND sh.schedule_purpose = 'OPERATIONAL'
                   AND sh.status NOT IN ('HOLD', 'CANCELLED')
                   AND sl.due_month = :validationMonth
                   AND sl.line_status NOT IN ('HOLD', 'CANCELLED')
                   AND sl.beneficiary_agent_id IS NOT NULL
                 ORDER BY sl.schedule_line_id
                """, validationRunId, validationMonth, "schedule_line_id");
        return query.setTupleTransformer((row, aliases) -> toScheduleSource(row)).getResultList();
    }

    public List<TransactionJournalSourceRow> findActualInsurerStatementSources(
            Long validationRunId, LocalDate validationMonth) {
        // 계약은 source_contract_id가 아닌 귀속행으로 연결한다. 여러 귀속행을 가진 거래는 제외한다.
        // 원수사 수기 수입도 대사의 실제 원장이 필요하므로 GA_MANUAL_PAYMENT를 포함한다.
        NativeQuery<?> query = createSourceQuery("""
                SELECT ct.commission_transaction_id, ta.contract_id, ct.policy_version_id, ct.commission_item_id,
                       NULL AS beneficiary_agent_id,
                       COALESCE(ct.paid_on, ct.due_date, ct.settlement_month) AS journal_date,
                       ct.amount, '실제 명세 ' || ct.source_business_key AS description
                  FROM fgc.commission_transaction ct
                  JOIN fgc.transaction_attribution ta
                    ON ta.commission_transaction_id = ct.commission_transaction_id
                   AND ta.attribution_scope = 'CONTRACT'
                  JOIN fgc.validation_target vt ON vt.contract_id = ta.contract_id
                 WHERE vt.validation_run_id = :validationRunId
                   AND vt.selection_status = 'SELECTED'
                   AND ct.payment_stage = 'INSURER_TO_GA'
                   AND ct.source_type IN ('INSURER_STATEMENT', 'GA_MANUAL_PAYMENT')
                   AND ct.cashflow_type = 'PAYMENT'
                   AND ct.status = 'CONFIRMED'
                   AND ct.settlement_month = :validationMonth
                   AND NOT EXISTS (
                       SELECT 1 FROM fgc.transaction_attribution ta2
                        WHERE ta2.commission_transaction_id = ct.commission_transaction_id
                          AND ta2.attribution_seq <> ta.attribution_seq
                   )
                 ORDER BY ct.commission_transaction_id
                """, validationRunId, validationMonth, "commission_transaction_id");
        return query.setTupleTransformer((row, aliases) -> toTransactionSource(row)).getResultList();
    }

    public List<TransactionJournalSourceRow> findConfirmedFcPayoutSources(
            Long validationRunId, LocalDate validationMonth) {
        // 정정·환수 원천과 DEDUCTION 거래가 확정 지급 분개에 섞이지 않도록 함께 제한한다.
        NativeQuery<?> query = createSourceQuery("""
                SELECT ct.commission_transaction_id, ta.contract_id, ct.policy_version_id, ct.commission_item_id,
                       ct.recipient_agent_id AS beneficiary_agent_id,
                       COALESCE(ct.paid_on, ct.due_date, ct.settlement_month) AS journal_date,
                       ct.amount, '확정 지급 건 ' || ct.source_business_key AS description
                  FROM fgc.commission_transaction ct
                  JOIN fgc.transaction_attribution ta
                    ON ta.commission_transaction_id = ct.commission_transaction_id
                   AND ta.attribution_scope = 'CONTRACT'
                  JOIN fgc.validation_target vt ON vt.contract_id = ta.contract_id
                 WHERE vt.validation_run_id = :validationRunId
                   AND vt.selection_status = 'SELECTED'
                   AND ct.payment_stage = 'GA_TO_FC'
                   AND ct.source_type IN ('GA_MANUAL_PAYMENT', 'GA_CONFIRMED_PAYMENT')
                   AND ct.cashflow_type = 'PAYMENT'
                   AND ct.status = 'CONFIRMED'
                   AND ct.settlement_month = :validationMonth
                   AND ct.recipient_agent_id IS NOT NULL
                   AND NOT EXISTS (
                       SELECT 1 FROM fgc.transaction_attribution ta2
                        WHERE ta2.commission_transaction_id = ct.commission_transaction_id
                          AND ta2.attribution_seq <> ta.attribution_seq
                   )
                 ORDER BY ct.commission_transaction_id
                """, validationRunId, validationMonth, "commission_transaction_id");
        return query.setTupleTransformer((row, aliases) -> toTransactionSource(row)).getResultList();
    }

    private NativeQuery<?> createSourceQuery(String sql, Long validationRunId,
                                             LocalDate validationMonth, String sourceIdAlias) {
        NativeQuery<?> query = entityManager.createNativeQuery(sql).unwrap(NativeQuery.class);
        query.setParameter("validationRunId", validationRunId);
        query.setParameter("validationMonth", validationMonth);
        query.addScalar(sourceIdAlias, Long.class);
        query.addScalar("contract_id", Long.class);
        query.addScalar("policy_version_id", Long.class);
        query.addScalar("commission_item_id", Long.class);
        query.addScalar("beneficiary_agent_id", Long.class);
        query.addScalar("journal_date", LocalDate.class);
        query.addScalar("amount", BigDecimal.class);
        query.addScalar("description", String.class);
        return query;
    }

    private ScheduleJournalSourceRow toScheduleSource(Object[] row) {
        ScheduleJournalSourceRow result = new ScheduleJournalSourceRow();
        result.setScheduleLineId((Long) row[0]);
        result.setContractId((Long) row[1]);
        result.setPolicyVersionId((Long) row[2]);
        result.setCommissionItemId((Long) row[3]);
        result.setBeneficiaryAgentId((Long) row[4]);
        result.setJournalDate((LocalDate) row[5]);
        result.setAmount((BigDecimal) row[6]);
        result.setDescription((String) row[7]);
        return result;
    }

    private TransactionJournalSourceRow toTransactionSource(Object[] row) {
        TransactionJournalSourceRow result = new TransactionJournalSourceRow();
        result.setCommissionTransactionId((Long) row[0]);
        result.setContractId((Long) row[1]);
        result.setPolicyVersionId((Long) row[2]);
        result.setCommissionItemId((Long) row[3]);
        result.setBeneficiaryAgentId((Long) row[4]);
        result.setJournalDate((LocalDate) row[5]);
        result.setAmount((BigDecimal) row[6]);
        result.setDescription((String) row[7]);
        return result;
    }
}
