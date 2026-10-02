package com.susukkang.fgc.transaction.repository;

import com.susukkang.fgc.transaction.entity.TransactionAttribution;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 설명 : 지급 귀속 저장소. 재작성 전에 기존 귀속을 즉시 삭제하여 순번 유일키를 비운다.
 *
 * @author hjKang
 * @since 2026-09-30
 * @version 1.0
 */
public interface TransactionAttributionRepository extends JpaRepository<TransactionAttribution, Long> {
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("DELETE FROM TransactionAttribution a WHERE a.commissionTransactionId = :paymentId")
    int deleteByPaymentId(@Param("paymentId") Long paymentId);
}
