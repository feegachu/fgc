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
