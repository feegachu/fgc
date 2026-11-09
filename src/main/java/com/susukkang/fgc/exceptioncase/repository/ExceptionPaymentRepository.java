package com.susukkang.fgc.exceptioncase.repository;

import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.common.exception.*;
import com.susukkang.fgc.transaction.dto.*;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;

/** 예외의 실제 지급 원천만 편집 대상으로 반환한다. 검출 당시 원천과 정정 이력은 보존한다. */
@Repository
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ExceptionPaymentRepository {
    private final EntityManager em;

    public record Source(Long id, String type, Long contractId, String sourceType,
                         String sourceId, Long validationRunId) {}
    public record CapScope(Long checkId, Long contractId, PaymentStage stage, LocalDate asOfDate,
                           boolean scheduledBasis, Long scheduleHeaderId, Long policyVersionId) {}
    public record ReconciliationSource(String resultType, BigDecimal expectedAmount, PaymentStage stage,
            Long contractId, Long expectedAgentId, Long commissionItemId, LocalDate month, Integer installmentNo, String itemCode) {}

    public LocalDate deferrableFrom(Long contractId) {
        if (contractId == null) return null;
        var values = em.createNativeQuery("SELECT CAST(contract_date+interval '12 months' AS date) FROM fgc.insurance_contract WHERE contract_id=:id")
                .setParameter("id", contractId).getResultList();
        return values.isEmpty() ? null : date(values.getFirst());
    }

    public ReconciliationSource reconciliationSource(Source source) {
        if (!"RECONCILIATION_MISMATCH".equals(source.type())) return null;
        var rows = em.createNativeQuery("""
                SELECT r.result_type, r.expected_total_amount, run.payment_stage, r.contract_id,
                  r.expected_agent_id, r.commission_item_id, run.settlement_month, r.installment_no, ci.item_code
                FROM fgc.reconciliation_result r JOIN fgc.reconciliation_run run USING (reconciliation_run_id)
                LEFT JOIN fgc.commission_item ci USING(commission_item_id)
                WHERE r.reconciliation_result_id::text=:id
                """).setParameter("id", source.sourceId()).getResultList();
        if (rows.isEmpty()) return null;
        Object[] r = (Object[]) rows.getFirst();
        return new ReconciliationSource((String) r[0], (BigDecimal) r[1], PaymentStage.valueOf((String) r[2]),
                number(r[3]), number(r[4]), number(r[5]), date(r[6]), r[7] == null ? null : ((Number)r[7]).intValue(), (String)r[8]);
    }

    public boolean hasRegisteredActual(ReconciliationSource source) {
        return ((Number) em.createNativeQuery("""
                SELECT count(*) FROM fgc.commission_transaction p
                WHERE p.status IN ('DRAFT','CONFIRMED') AND p.cashflow_type='PAYMENT' AND p.payment_stage=:stage
                  AND p.settlement_month=:month AND p.commission_item_id=:item
                  AND p.recipient_agent_id IS NOT DISTINCT FROM CAST(:agent AS bigint)
                  AND (p.installment_no IS NULL OR p.installment_no=CAST(:installment AS integer))
                  AND EXISTS(SELECT 1 FROM fgc.transaction_attribution a
                    WHERE a.commission_transaction_id=p.commission_transaction_id AND a.contract_id=:contract)
                """).setParameter("stage", source.stage().name()).setParameter("month", source.month())
                .setParameter("item", source.commissionItemId()).setParameter("agent", source.stage() == PaymentStage.GA_TO_FC ? source.expectedAgentId() : null)
                .setParameter("installment", source.installmentNo()).setParameter("contract", source.contractId()).getSingleResult()).longValue() > 0;
    }

    public CapScope capScope(Long id) {
        var rows = em.createNativeQuery("""
                SELECT c.cap_check_id, c.contract_id, c.payment_stage, c.as_of_date,
                  c.candidate_transaction_id IS NULL,
                  (SELECT MIN(l.schedule_header_id) FROM fgc.cap_check_detail d JOIN fgc.schedule_line l USING (schedule_line_id)
                   WHERE d.cap_check_id=c.cap_check_id),
                  (SELECT MIN(h.policy_version_id) FROM fgc.cap_check_detail d JOIN fgc.schedule_line l USING (schedule_line_id)
                   JOIN fgc.schedule_header h USING (schedule_header_id) WHERE d.cap_check_id=c.cap_check_id)
                FROM fgc.exception_case e JOIN fgc.cap_check c ON c.cap_check_id=e.cap_check_id
                  OR (e.cap_check_id IS NULL AND e.source_entity_type='CAP_CHECK' AND c.cap_check_id::text=e.source_entity_id)
                WHERE e.exception_case_id=:id
                """).setParameter("id", id).getResultList();
        if (rows.isEmpty()) return null;
        Object[] r = (Object[]) rows.getFirst();
        return new CapScope(number(r[0]), number(r[1]), PaymentStage.valueOf((String)r[2]), date(r[3]), (Boolean)r[4], number(r[5]), number(r[6]));
    }

    public Source source(Long id) {
        var rows = em.createNativeQuery("""
                SELECT exception_type, contract_id, source_entity_type, source_entity_id, last_detected_run_id
                FROM fgc.exception_case WHERE exception_case_id = :id
                """).setParameter("id", id).getResultList();
        if (rows.isEmpty()) throw new FgcBusinessException(FgcErrorCode.COMMON_004,
                "exceptionCaseId", Map.of("field", "exceptionCaseId", "id", id), null);
        Object[] r = (Object[]) rows.getFirst();
        return new Source(id, (String) r[0], number(r[1]), (String) r[2], (String) r[3], number(r[4]));
    }

    public List<Long> newWarningsForCorrectedViolation(Long exceptionId, Long paymentId, List<Long> capCheckIds) {
        if (capCheckIds.isEmpty()) return List.of();
        return em.createNativeQuery("""
                SELECT warning.exception_case_id
                FROM fgc.exception_case violation
                JOIN fgc.cap_check original ON original.cap_check_id = violation.cap_check_id
                    OR (violation.cap_check_id IS NULL AND violation.source_entity_type = 'CAP_CHECK'
                        AND original.cap_check_id::text = violation.source_entity_id)
                JOIN fgc.cap_check recheck ON recheck.contract_id = original.contract_id
                    AND recheck.payment_stage = original.payment_stage
                    AND recheck.cap_rule_set_id = original.cap_rule_set_id
                JOIN fgc.exception_case warning ON warning.cap_check_id = recheck.cap_check_id
                WHERE violation.exception_case_id = :exceptionId AND original.result_status = 'VIOLATION'
                  AND recheck.cap_check_id IN (:capCheckIds) AND recheck.result_status = 'WARNING'
                  AND warning.exception_type = 'CAP_WARNING' AND warning.status = 'NEW'
                  AND warning.source_entity_type = 'COMMISSION_TRANSACTION' AND warning.source_entity_id = :paymentId
                ORDER BY warning.exception_case_id
                """, Long.class)
                .setParameter("exceptionId", exceptionId).setParameter("paymentId", paymentId.toString())
                .setParameter("capCheckIds", capCheckIds).getResultList();
    }

    public List<Long> paymentIds(Long id) {
        return em.createNativeQuery("""
                WITH e AS (SELECT * FROM fgc.exception_case WHERE exception_case_id = :id),
                original AS (
                  SELECT ct.commission_transaction_id FROM e JOIN fgc.commission_transaction ct
                    ON e.source_entity_type = 'COMMISSION_TRANSACTION'
                   AND ct.commission_transaction_id::text = e.source_entity_id
                  UNION
                  SELECT ta.commission_transaction_id FROM e
                    JOIN fgc.reconciliation_match m ON e.source_entity_type = 'RECONCILIATION_RESULT'
                     AND m.reconciliation_result_id::text = e.source_entity_id
                    JOIN fgc.transaction_attribution ta ON ta.transaction_attribution_id = m.transaction_attribution_id
                  UNION
                  SELECT p.commission_transaction_id FROM e JOIN fgc.reconciliation_result r
                    ON e.source_entity_type='RECONCILIATION_RESULT' AND r.reconciliation_result_id::text=e.source_entity_id
                    JOIN fgc.reconciliation_run run USING(reconciliation_run_id)
                    JOIN fgc.commission_transaction p ON p.status='DRAFT' AND p.cashflow_type='PAYMENT'
                      AND p.payment_stage=run.payment_stage AND p.settlement_month=run.settlement_month
                      AND p.commission_item_id=r.commission_item_id AND p.recipient_agent_id IS NOT DISTINCT FROM
                        CASE WHEN run.payment_stage='GA_TO_FC' THEN r.expected_agent_id ELSE NULL END
                      AND (p.installment_no IS NULL OR p.installment_no=r.installment_no)
                    WHERE r.result_type='ACTUAL_MISSING' AND EXISTS(SELECT 1 FROM fgc.transaction_attribution a
                      WHERE a.commission_transaction_id=p.commission_transaction_id AND a.contract_id=r.contract_id)
                  UNION
                  SELECT ct.commission_transaction_id FROM e
                    JOIN fgc.cap_check c ON c.cap_check_id = e.cap_check_id
                      OR (e.source_entity_type = 'CAP_CHECK' AND c.cap_check_id::text = e.source_entity_id)
                    JOIN fgc.commission_transaction ct ON ct.commission_transaction_id = c.candidate_transaction_id
                      OR (c.candidate_transaction_id IS NULL AND ct.payment_stage = c.payment_stage
                        AND EXISTS (SELECT 1 FROM fgc.transaction_attribution ta
                          WHERE ta.commission_transaction_id = ct.commission_transaction_id AND ta.contract_id = c.contract_id
                            AND ta.attribution_date <= c.as_of_date))
                  UNION
                  SELECT (a.after_value ->> 'paymentId')::bigint FROM e JOIN fgc.audit_log a
                    ON a.entity_type = 'EXCEPTION_CASE' AND a.entity_id = e.exception_case_id::text
                   AND a.action_code = 'EXCEPTION_PAYMENT_CORRECTION'
                ) SELECT DISTINCT ct.commission_transaction_id FROM original o
                  JOIN fgc.commission_transaction ct USING (commission_transaction_id)
                WHERE ct.status IN ('DRAFT','CONFIRMED') ORDER BY ct.commission_transaction_id
                """, Long.class).setParameter("id", id).getResultList();
    }

    public CommissionPaymentCreateRequest suggestedPayment(Source source) {
        if (!"RECONCILIATION_MISMATCH".equals(source.type())) return null;
        var reco = reconciliationSource(source);
        if (reco == null || !"ACTUAL_MISSING".equals(reco.resultType())) return null;
        var rows = em.createNativeQuery("""
                SELECT r.contract_id, r.expected_agent_id, r.commission_item_id, r.expected_total_amount,
                  run.settlement_month, run.payment_stage,
                  COALESCE((SELECT MIN(sl.due_date) FROM fgc.reconciliation_match m
                    JOIN fgc.schedule_line sl ON sl.schedule_line_id = m.schedule_line_id
                    WHERE m.reconciliation_result_id = r.reconciliation_result_id), run.settlement_month)
                FROM fgc.reconciliation_result r JOIN fgc.reconciliation_run run USING (reconciliation_run_id)
                WHERE r.reconciliation_result_id::text = :sourceId
                """).setParameter("sourceId", source.sourceId()).getResultList();
        if (rows.isEmpty()) return null;
        Object[] r = (Object[]) rows.getFirst();
        if (r[0] == null || r[2] == null || r[3] == null) return null;
        var stage = PaymentStage.valueOf((String) r[5]);
        LocalDate month = date(r[4]), due = date(r[6]);
        return new CommissionPaymentCreateRequest("GA_MANUAL_PAYMENT", "EXCP:" + source.id() + ":NEW",
                number(r[0]), stage == PaymentStage.GA_TO_FC ? number(r[1]) : null, number(r[2]),
                (BigDecimal) r[3], month, "PAYMENT", due, stage, null,
                List.of(new CommissionPaymentAttributionRequest(number(r[0]), due, (BigDecimal) r[3],
                        InclusionDecisionStatus.INCLUDED, null, "예외 원인 정정", "직접 귀속", null, AttributionMethod.DIRECT)),
                null, "대사 불일치 정정");
    }

    @Transactional
    public void lockValidations(Long id) {
        var rows = em.createNativeQuery("""
                SELECT v.validation_run_id, v.status FROM fgc.validation_run v
                WHERE v.validation_run_id IN (
                  SELECT last_detected_run_id FROM fgc.exception_case WHERE exception_case_id = :id
                  UNION SELECT first_detected_run_id FROM fgc.exception_case WHERE exception_case_id = :id)
                ORDER BY v.validation_run_id FOR UPDATE
                """).setParameter("id", id).getResultList();
        for (Object value : rows) if ("FINALIZED".equals(((Object[]) value)[1])) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_003, Map.of("validationRunId", ((Object[]) value)[0]));
        }
    }

    public Long postedJournal(Long paymentId) {
        var ids = em.createNativeQuery("""
                SELECT journal_header_id FROM fgc.journal_header
                WHERE source_entity_type = 'COMMISSION_TRANSACTION' AND source_entity_id = :id
                  AND status = 'POSTED' AND journal_type IN ('CONFIRMED_FC_PAYOUT','ACTUAL_INSURER_STATEMENT')
                ORDER BY journal_header_id
                """, Long.class).setParameter("id", paymentId.toString()).getResultList();
        if (ids.size() > 1) throw new FgcBusinessException(FgcErrorCode.COMMON_002, Map.of("field", "paymentJournals"));
        return ids.isEmpty() ? null : (Long) ids.getFirst();
    }

    public List<Long> reconciliationRuns(Source source) {
        if (!"RECONCILIATION_MISMATCH".equals(source.type())) return List.of();
        return em.createNativeQuery("""
                WITH RECURSIVE revisions(id) AS (
                  SELECT reconciliation_run_id FROM fgc.reconciliation_result WHERE reconciliation_result_id::text = :id
                  UNION ALL SELECT r.reconciliation_run_id FROM fgc.reconciliation_run r
                    JOIN revisions p ON r.corrects_reconciliation_run_id = p.id
                ) SELECT r.reconciliation_run_id FROM fgc.reconciliation_run r JOIN revisions p ON p.id = r.reconciliation_run_id
                  WHERE NOT r.superseded_yn AND r.status = 'COMPLETED' ORDER BY r.reconciliation_run_id FOR UPDATE OF r
                """, Long.class).setParameter("id", source.sourceId()).getResultList();
    }

    public List<com.susukkang.fgc.exceptioncase.dto.ExceptionReconciliationIssue> remainingIssues(Source source) {
        if (!"RECONCILIATION_MISMATCH".equals(source.type())) return List.of();
        List<?> rows = em.createNativeQuery("""
                WITH RECURSIVE revisions(id) AS (
                  SELECT reconciliation_run_id FROM fgc.reconciliation_result WHERE reconciliation_result_id::text=:id
                  UNION ALL SELECT r.reconciliation_run_id FROM fgc.reconciliation_run r
                    JOIN revisions p ON r.corrects_reconciliation_run_id=p.id)
                SELECT n.reconciliation_result_id,n.result_type,n.expected_total_amount,n.actual_total_amount,
                  n.difference_amount,n.installment_no,ea.agent_name,aa.agent_name,run.reconciliation_run_id,
                  run.settlement_month,run.payment_stage
                FROM revisions p JOIN fgc.reconciliation_run run ON run.reconciliation_run_id=p.id
                JOIN fgc.reconciliation_result n ON n.reconciliation_run_id=run.reconciliation_run_id
                JOIN fgc.reconciliation_result old ON old.reconciliation_result_id::text=:id
                LEFT JOIN fgc.agent ea ON ea.agent_id=n.expected_agent_id
                LEFT JOIN fgc.agent aa ON aa.agent_id=n.actual_agent_id
                WHERE NOT run.superseded_yn AND run.status='COMPLETED' AND n.result_type<>'MATCHED'
                  AND NOT (n.result_type='INVALID_CONTRACT_PAYMENT' AND fgc.reconciliation_recovery_complete(n.reconciliation_result_id))
                  AND (n.match_group_key=old.match_group_key OR EXISTS (
                    SELECT 1 FROM fgc.reconciliation_match om JOIN fgc.reconciliation_match nm
                      ON (om.schedule_line_id IS NOT NULL AND nm.schedule_line_id=om.schedule_line_id)
                      OR (om.transaction_attribution_id IS NOT NULL AND nm.transaction_attribution_id=om.transaction_attribution_id)
                    WHERE om.reconciliation_result_id=old.reconciliation_result_id
                      AND nm.reconciliation_result_id=n.reconciliation_result_id))
                ORDER BY n.reconciliation_result_id
                """).setParameter("id", source.sourceId()).getResultList();
        return rows.stream().map(value -> {
            Object[] r = (Object[]) value;
            String type = (String) r[1];
            return new com.susukkang.fgc.exceptioncase.dto.ExceptionReconciliationIssue(number(r[0]), type,
                    ReconResultType.valueOf(type).label(), (BigDecimal)r[2], (BigDecimal)r[3], (BigDecimal)r[4],
                    r[5] == null ? null : ((Number)r[5]).intValue(), (String)r[6], (String)r[7],
                    "/reconciliations?runId=" + r[8] + "&resultId=" + r[0] + "&month=" + date(r[9]).toString().substring(0,7) + "&stage=" + r[10]);
        }).toList();
    }

    public Long replacement(String group) {
        return (Long) em.createNativeQuery("SELECT replacement_payment_id FROM fgc.payment_journal_correction WHERE correction_group_key = :key")
                .setParameter("key", group).getSingleResult();
    }

    @Transactional
    public boolean registerRecovery(Source source, Long originalPayment, Long deductionPayment, Long candidate,
                                    int version, String documentNo) {
        em.createNativeQuery("""
                INSERT INTO fgc.exception_payment_recovery(original_payment_id,contract_id,deduction_payment_id,
                    clawback_calculation_id,generated_document_id,exception_case_id)
                SELECT :original,:contract,:deduction,c.clawback_calculation_id,d.generated_document_id,:exception
                FROM fgc.clawback_calculation c
                LEFT JOIN LATERAL (
                  SELECT d.generated_document_id FROM fgc.generated_document d
                  LEFT JOIN fgc.clawback_notice_payment_scope scope USING(generated_document_id)
                  WHERE d.source_entity_id=c.clawback_case_id AND d.source_entity_type='CLAWBACK_CASE'
                    AND d.document_kind='CLAWBACK_NOTICE' AND d.source_version_no=c.version_no AND (
                      (scope.original_payment_id=:original AND d.document_no || '-v' || d.version_no=:document)
                      OR (scope.original_payment_id IS NULL AND d.document_no=:document))
                  ORDER BY d.version_no DESC LIMIT 1
                ) d ON TRUE
                WHERE c.candidate_id=:candidate AND c.version_no=:version
                  AND (CAST(:document AS text) IS NULL OR d.generated_document_id IS NOT NULL)
                ON CONFLICT DO NOTHING
                """).setParameter("original", originalPayment).setParameter("contract", source.contractId())
                .setParameter("deduction", deductionPayment).setParameter("exception", source.id()).setParameter("candidate", candidate)
                .setParameter("version", version).setParameter("document", documentNo).executeUpdate();
        return Boolean.TRUE.equals(em.createNativeQuery("""
                SELECT EXISTS (SELECT 1 FROM fgc.exception_payment_recovery r JOIN fgc.clawback_calculation c USING(clawback_calculation_id)
                  WHERE r.original_payment_id=:original AND r.contract_id=:contract AND r.deduction_payment_id=:deduction
                    AND c.candidate_id=:candidate AND c.version_no=:version)
                """).setParameter("original", originalPayment).setParameter("contract", source.contractId()).setParameter("deduction", deductionPayment)
                .setParameter("candidate", candidate).setParameter("version", version).getSingleResult());
    }

    public boolean recoveryComplete(Source source) {
        return Boolean.TRUE.equals(em.createNativeQuery("SELECT fgc.reconciliation_recovery_complete(CAST(:id AS bigint))")
                .setParameter("id", source.sourceId()).getSingleResult());
    }

    @Transactional
    public void bindMissingActual(Long paymentId, Source source) {
        em.createNativeQuery("""
                UPDATE fgc.commission_transaction p SET installment_no = r.installment_no
                FROM fgc.reconciliation_result r
                WHERE r.reconciliation_result_id::text = :sourceId AND p.commission_transaction_id = :id
                  AND p.status = 'DRAFT' AND p.source_contract_id = r.contract_id
                  AND p.commission_item_id = r.commission_item_id
                """).setParameter("id", paymentId).setParameter("sourceId", source.sourceId()).executeUpdate();
        em.createNativeQuery("""
                UPDATE fgc.transaction_attribution a SET schedule_line_id = (
                  SELECT MIN(m.schedule_line_id) FROM fgc.reconciliation_match m
                  WHERE m.reconciliation_result_id = r.reconciliation_result_id AND m.schedule_line_id IS NOT NULL)
                FROM fgc.reconciliation_result r, fgc.commission_transaction p
                WHERE r.reconciliation_result_id::text = :sourceId AND p.commission_transaction_id = :id
                  AND p.status = 'DRAFT' AND a.commission_transaction_id = p.commission_transaction_id
                  AND a.contract_id = r.contract_id AND p.commission_item_id = r.commission_item_id
                """).setParameter("id", paymentId).setParameter("sourceId", source.sourceId()).executeUpdate();
        em.clear();
    }

    public List<com.susukkang.fgc.journal.dto.JournalRepostLineCommand> repostLines(Long journal, BigDecimal amount, String reason) {
        List<?> rows = em.createNativeQuery("""
                SELECT l.line_no, a.account_code, l.debit_amount, l.credit_amount
                FROM fgc.journal_line l JOIN fgc.journal_account a USING (journal_account_id)
                WHERE journal_header_id = :id ORDER BY l.line_no
                """).setParameter("id", journal).getResultList();
        return rows.stream().map(value -> {
            Object[] r = (Object[]) value;
            return new com.susukkang.fgc.journal.dto.JournalRepostLineCommand(((Number) r[0]).intValue(),
                    (String) r[1], ((BigDecimal) r[2]).signum() > 0 ? amount : BigDecimal.ZERO,
                    ((BigDecimal) r[3]).signum() > 0 ? amount : BigDecimal.ZERO, reason);
        }).toList();
    }

    private static Long number(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private static LocalDate date(Object value) { return value instanceof java.sql.Date d ? d.toLocalDate() : (LocalDate) value; }
}
