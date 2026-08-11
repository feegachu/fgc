package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;

/** FUN-031 계약별 한도 산입 지급액 합산 서비스. */
public interface CapIncludedAmountService {

    // 지급 확정 전 기존 확정액과 이번 지급 건의 산입액 합산
    BigDecimal calculatePreConfirmAmount(Long contractId, Long transactionId, PaymentStage paymentStage);

    // 월 검증 실행 시 계약별 확정 산입액 전체 재합산
    BigDecimal recalculateTotalAmount(Long contractId, PaymentStage paymentStage);
}
