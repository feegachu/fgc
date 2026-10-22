package com.susukkang.fgc.transaction.repository;

import com.susukkang.fgc.transaction.domain.CommissionPaymentAttributionCommand;
import com.susukkang.fgc.transaction.domain.CommissionPaymentCommand;
import com.susukkang.fgc.transaction.entity.CommissionTransaction;
import com.susukkang.fgc.transaction.entity.TransactionAttribution;
import jakarta.persistence.EntityManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * 설명 : 지급 등록·수정·귀속의 JPA 저장 및 조건부 확정.
 * 서비스 트랜잭션에 참여하며 독립 트랜잭션을 시작하지 않는다.
 *
 * @author hjKang
 * @since 2026-09-30
 * @version 1.0
 */
@Repository
@RequiredArgsConstructor
public class CommissionPaymentWriteRepository {
    private final CommissionTransactionRepository transactionRepository;
    private final TransactionAttributionRepository attributionRepository;
    private final EntityManager entityManager;

    public boolean isJournalCorrectionReplacement(Long paymentId, String correctionGroupKey) {
        return ((Number) entityManager.createNativeQuery("""
                SELECT COUNT(*) FROM fgc.payment_journal_correction
                WHERE replacement_payment_id = :paymentId AND correction_group_key = :groupKey
                """).setParameter("paymentId", paymentId).setParameter("groupKey", correctionGroupKey)
                .getSingleResult()).longValue() == 1;
    }

    public void insertTransaction(CommissionPaymentCommand command) {
        CommissionTransaction saved = transactionRepository.saveAndFlush(
                CommissionTransaction.draft(command, findInsurerId(command.getNaturalContractId())));
        command.setPaymentId(saved.getCommissionTransactionId());
    }

    /** 호출자는 먼저 지급 행을 잠근다. 동일 값의 수정도 DB 타임스탬프 트리거를 실행한다. */
    public int updateTransaction(CommissionPaymentCommand command) {
        return transactionRepository.updateDraft(command, findInsurerId(command.getNaturalContractId()));
    }

    public void deleteAttributions(Long paymentId) {
        attributionRepository.deleteByPaymentId(paymentId);
    }

    /** 일반 초안 삭제로 끊을 수 없는 회계·대사·환수·정정 연결. FK도 최종 방어선으로 유지한다. */
    public boolean hasDeletionDependencies(Long paymentId) {
        return (Boolean) entityManager.createNativeQuery("""
                SELECT EXISTS (
                    SELECT 1 FROM fgc.payment_journal_correction
                    WHERE original_payment_id = :id OR replacement_payment_id = :id
                    UNION ALL
                    SELECT 1 FROM fgc.journal_header
                    WHERE source_entity_type = 'COMMISSION_TRANSACTION' AND source_entity_id = CAST(:id AS text)
                    UNION ALL
                    SELECT 1 FROM fgc.recovery_transaction WHERE offset_transaction_id = :id
                    UNION ALL
                    SELECT 1 FROM fgc.reconciliation_match rm JOIN fgc.transaction_attribution ta
                        ON ta.transaction_attribution_id = rm.transaction_attribution_id
                    WHERE ta.commission_transaction_id = :id
                    UNION ALL
                    SELECT 1 FROM fgc.clawback_line cl JOIN fgc.transaction_attribution ta
                        ON ta.transaction_attribution_id = cl.transaction_attribution_id
                    WHERE ta.commission_transaction_id = :id
                    UNION ALL
                    SELECT 1 FROM fgc.cap_check WHERE candidate_transaction_id = :id AND check_kind <> 'PRE_CONFIRM'
                    UNION ALL
                    SELECT 1 FROM fgc.cap_check_detail cd
                        JOIN fgc.transaction_attribution ta ON ta.transaction_attribution_id = cd.transaction_attribution_id
                        JOIN fgc.cap_check cc ON cc.cap_check_id = cd.cap_check_id
                    WHERE ta.commission_transaction_id = :id
                      AND (cc.check_kind <> 'PRE_CONFIRM' OR cc.candidate_transaction_id IS DISTINCT FROM :id)
                )
                """).setParameter("id", paymentId).getSingleResult();
    }

    /** 검출 근거·예외 연결을 남기고, 삭제된 후보가 실제 계약 판정으로 노출되지 않도록 표시한다. */
    public void archiveDraftChecks(Long paymentId) {
        entityManager.flush();
        entityManager.createNativeQuery("""
                UPDATE fgc.cap_check
                   SET calculation_snapshot = calculation_snapshot || jsonb_build_object('deletedCandidateTransactionId', candidate_transaction_id),
                       candidate_transaction_id = NULL
                 WHERE candidate_transaction_id = :id AND check_kind = 'PRE_CONFIRM'
                """).setParameter("id", paymentId).executeUpdate();
        entityManager.clear();
    }

    public int deleteDraft(Long paymentId) {
        entityManager.flush();
        int deleted = entityManager.createNativeQuery("""
                DELETE FROM fgc.commission_transaction
                 WHERE commission_transaction_id = :id AND status = 'DRAFT' AND source_type = 'GA_MANUAL_PAYMENT'
                """).setParameter("id", paymentId).executeUpdate();
        entityManager.clear();
        return deleted;
    }

    public void insertAttributions(List<CommissionPaymentAttributionCommand> commands) {
        attributionRepository.saveAllAndFlush(commands.stream().map(TransactionAttribution::from).toList());
    }

    /** PostgreSQL 배열 변환과 상태 조건을 하나의 원자적 UPDATE로 유지한다. */
    public int confirm(Long paymentId, String idempotencyKey, String capCheckIdsCsv) {
        entityManager.flush();
        int updated = entityManager.createNativeQuery("""
                UPDATE fgc.commission_transaction
                   SET status = 'CONFIRMED',
                       confirm_idempotency_key = :idempotencyKey,
                       confirm_cap_check_ids = CAST(string_to_array(:capCheckIdsCsv, ',') AS bigint[])
                 WHERE commission_transaction_id = :paymentId
                   AND status = 'DRAFT'
                   AND source_type = 'GA_MANUAL_PAYMENT'
                """)
                .setParameter("paymentId", paymentId)
                .setParameter("idempotencyKey", idempotencyKey)
                .setParameter("capCheckIdsCsv", capCheckIdsCsv)
                .executeUpdate();
        // 벌크 SQL은 관리 중인 엔티티를 갱신하지 않으므로 후속 조회가 DB 상태를 다시 읽게 한다.
        entityManager.clear();
        return updated;
    }

    private Long findInsurerId(Long contractId) {
        if (contractId == null) {
            return null;
        }
        return entityManager.createQuery("SELECT c.insurerId FROM InsuranceContract c WHERE c.contractId = :id", Long.class)
                .setParameter("id", contractId).getResultStream().findFirst().orElse(null);
    }
}
