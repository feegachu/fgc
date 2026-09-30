package com.susukkang.fgc.transaction.repository;

import com.susukkang.fgc.transaction.entity.CommissionTransaction;
import com.susukkang.fgc.transaction.domain.CommissionPaymentCommand;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 설명 : 수수료 지급 원장의 기본 저장·조회 저장소.
 *
 * @author hjKang
 * @since 2026-09-30
 * @version 1.0
 */
public interface CommissionTransactionRepository extends JpaRepository<CommissionTransaction, Long> {
    /**
     * 기존 UPDATE의 상태 조건을 원자적으로 보존한다. 값이 같은 재수정도 UPDATE를 실행해
     * DB updated_at 트리거가 작동하며, 벌크 변경 뒤 관리 중인 과거 객체를 비운다.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            UPDATE CommissionTransaction t
               SET t.paymentStage = :#{#command.paymentStage},
                   t.sourceType = :#{#command.sourceType},
                   t.sourceBusinessKey = :#{#command.sourceBusinessKey},
                   t.sourceContractId = :#{#command.naturalContractId},
                   t.insurerId = :insurerId,
                   t.recipientAgentId = :#{#command.agentId},
                   t.commissionItemId = :#{#command.commissionItemId},
                   t.policyVersionId = :#{#command.policyVersionId},
                   t.settlementMonth = :#{#command.settlementMonth},
                   t.dueDate = :#{#command.dueDate},
                   t.installmentNo = :#{#command.installmentNo},
                   t.amount = :#{#command.amount},
                   t.cashflowType = :#{#command.cashflowType},
                   t.evidenceRef = :#{#command.evidenceRef},
                   t.note = :#{#command.note}
             WHERE t.commissionTransactionId = :#{#command.paymentId}
               AND t.status = com.susukkang.fgc.common.code.CommissionPaymentStatus.DRAFT
               AND t.sourceType = 'GA_MANUAL_PAYMENT'
            """)
    int updateDraft(@Param("command") CommissionPaymentCommand command, @Param("insurerId") Long insurerId);
}
