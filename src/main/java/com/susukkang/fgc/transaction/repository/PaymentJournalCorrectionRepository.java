package com.susukkang.fgc.transaction.repository;

import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
@Transactional(propagation = Propagation.MANDATORY)
public class PaymentJournalCorrectionRepository {
    private final EntityManager entityManager;

    public boolean lockPayment(Long id) {
        return !entityManager.createNativeQuery("""
                SELECT commission_transaction_id FROM fgc.commission_transaction
                WHERE commission_transaction_id = :id FOR UPDATE
                """).setParameter("id", id).getResultList().isEmpty();
    }

    public int cancel(Long id) {
        int changed = entityManager.createNativeQuery("""
                UPDATE fgc.commission_transaction SET status = 'CANCELLED', updated_at = clock_timestamp()
                WHERE commission_transaction_id = :id AND status = 'CONFIRMED'
                """).setParameter("id", id).executeUpdate();
        entityManager.clear();
        return changed;
    }

    /** 등록 API에 없는 원 명세/귀속 식별자는 대체 지급이 아직 DRAFT일 때 승계한다. */
    public void copySourceMetadata(Long oldId, Long newId) {
        entityManager.createNativeQuery("""
                UPDATE fgc.commission_transaction n SET installment_no = o.installment_no, paid_on = o.paid_on,
                    statement_batch_id = o.statement_batch_id, insurer_id = o.insurer_id
                FROM fgc.commission_transaction o
                WHERE n.commission_transaction_id = :newId AND o.commission_transaction_id = :oldId
                """).setParameter("newId", newId).setParameter("oldId", oldId).executeUpdate();
        entityManager.createNativeQuery("""
                UPDATE fgc.transaction_attribution n SET schedule_line_id = o.schedule_line_id,
                    agent_id = o.agent_id, source_agent_code = o.source_agent_code
                FROM fgc.transaction_attribution o
                WHERE n.commission_transaction_id = :newId AND o.commission_transaction_id = :oldId
                """).setParameter("newId", newId).setParameter("oldId", oldId).executeUpdate();
        entityManager.clear();
    }

    public void insertLink(String groupKey, Long oldId, Long newId) {
        entityManager.createNativeQuery("""
                INSERT INTO fgc.payment_journal_correction
                    (correction_group_key, original_payment_id, replacement_payment_id)
                VALUES (:groupKey, :oldId, :newId)
                """).setParameter("groupKey", groupKey).setParameter("oldId", oldId)
                .setParameter("newId", newId).executeUpdate();
    }
}
