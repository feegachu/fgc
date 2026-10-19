package com.susukkang.fgc.reconciliation.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;

@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentCorrectionReconciliationRepository {
    private final EntityManager entityManager;

    public List<?> lockValidations(Long originalRun, Long paymentId) {
        return entityManager.createNativeQuery("""
                SELECT vr.validation_run_id, vr.status FROM fgc.validation_run vr
                WHERE vr.validation_run_id = :originalRun OR vr.validation_run_id IN (
                    SELECT r.validation_run_id FROM fgc.reconciliation_run r
                    JOIN fgc.commission_transaction ct ON ct.commission_transaction_id = :paymentId
                    WHERE r.settlement_month = ct.settlement_month AND r.payment_stage = ct.payment_stage
                    AND NOT r.superseded_yn)
                ORDER BY vr.validation_run_id FOR UPDATE
                """).setParameter("originalRun", originalRun)
                .setParameter("paymentId", paymentId).getResultList();
    }

    public List<?> lockRuns(Long paymentId, Long contractId) {
        return entityManager.createNativeQuery("""
                SELECT r.reconciliation_run_id, r.status FROM fgc.reconciliation_run r
                JOIN fgc.commission_transaction ct ON ct.commission_transaction_id = :paymentId
                JOIN fgc.insurance_contract c ON c.contract_id = :contractId
                LEFT JOIN fgc.validation_run vr ON vr.validation_run_id = r.validation_run_id
                WHERE r.settlement_month = ct.settlement_month AND r.payment_stage = ct.payment_stage
                AND r.insurer_id = CASE WHEN ct.payment_stage = 'GA_TO_FC' THEN c.insurer_id ELSE ct.insurer_id END
                AND NOT r.superseded_yn AND COALESCE(vr.status, '') <> 'FINALIZED'
                ORDER BY r.reconciliation_run_id FOR UPDATE OF r
                """).setParameter("paymentId", paymentId)
                .setParameter("contractId", contractId).getResultList();
    }

    public int supersede(Long oldId) {
        return entityManager.createNativeQuery("""
                UPDATE fgc.reconciliation_run SET superseded_yn = true
                WHERE reconciliation_run_id = :id AND NOT superseded_yn AND status = 'COMPLETED'
                """).setParameter("id", oldId).executeUpdate();
    }

    public void linkRevision(Long oldId, Long newId) {
        entityManager.createNativeQuery("""
                UPDATE fgc.reconciliation_run SET corrects_reconciliation_run_id = :oldId,
                tolerance_policy_version_id = (SELECT tolerance_policy_version_id
                    FROM fgc.reconciliation_run WHERE reconciliation_run_id = :oldId)
                WHERE reconciliation_run_id = :newId
                """).setParameter("oldId", oldId).setParameter("newId", newId).executeUpdate();
    }

    public List<?> lockMatchedExceptions(Long oldId, Long newId) {
        return entityManager.createNativeQuery("""
                SELECT e.exception_case_id, e.status FROM fgc.exception_case e
                JOIN fgc.reconciliation_result old ON e.source_entity_type = 'RECONCILIATION_RESULT'
                    AND e.source_entity_id = CAST(old.reconciliation_result_id AS varchar)
                WHERE e.exception_type = 'RECONCILIATION_MISMATCH' AND e.status IN ('NEW', 'IN_REVIEW')
                AND old.reconciliation_run_id = :oldId
                AND EXISTS (SELECT 1 FROM fgc.reconciliation_result n
                    WHERE n.reconciliation_run_id = :newId AND n.match_group_key = old.match_group_key
                    AND n.result_type = 'MATCHED')
                ORDER BY e.exception_case_id FOR UPDATE OF e
                """).setParameter("oldId", oldId).setParameter("newId", newId).getResultList();
    }

    public String loginId(Long actor) {
        return (String) entityManager.createNativeQuery("SELECT login_id FROM fgc.app_user WHERE user_id = :id")
                .setParameter("id", actor).getSingleResult();
    }
}
