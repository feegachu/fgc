package com.susukkang.fgc.arbitrage.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 설명 : 계약에 귀속된 확정 지급·차감 수수료 합계를 전달한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Getter
@Setter
public class ConfirmedCommissionSummary {
    private BigDecimal confirmedPaymentAmount; // 확정 PAYMENT 합계
    private BigDecimal confirmedDeductionAmount; // 확정 DEDUCTION 합계
    private BigDecimal paidCommissionAmount; // 확정 지급에서 확정 차감을 뺀 순액
}
