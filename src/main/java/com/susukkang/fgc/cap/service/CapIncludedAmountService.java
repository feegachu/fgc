package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;

/**
 * 설명 : FUN-031 계약별 한도 산입 지급액 합산 서비스
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public interface CapIncludedAmountService {

    /**
     * 설명 : 지급 확정 전 기존 확정액과 이번 지급 건의 산입액을 합산한다
     *
     * @param contractId 계약 ID
     * @param transactionId 현재 확정할 지급 건 ID
     * @param paymentStage 지급 단계
     * @return 계약별 한도 산입액 합계
     * @author hjKang
     * @since 2026-08-12
     */
    BigDecimal calculatePreConfirmAmount(Long contractId, Long transactionId, PaymentStage paymentStage);

    /**
     * 설명 : 월 검증 실행 시 계약별 확정 산입액을 전체 재합산한다
     *
     * @param contractId 계약 ID
     * @param paymentStage 지급 단계
     * @return 계약별 확정 한도 산입액 합계
     * @author hjKang
     * @since 2026-08-12
     */
    BigDecimal recalculateTotalAmount(Long contractId, PaymentStage paymentStage);
}
