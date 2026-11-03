package com.susukkang.fgc.arbitrage.dto;

import java.math.BigDecimal;

/** 기지급/차감과 잔여 예정액을 한 SQL 문장의 동일 PostgreSQL 스냅샷에서 읽은 결과. */
public record CommissionBalanceSnapshot(BigDecimal confirmedPaymentAmount, BigDecimal confirmedDeductionAmount,
                                        PlannedCommissionSummary planned) {
    public BigDecimal paidCommissionAmount() {
        return confirmedPaymentAmount.subtract(confirmedDeductionAmount);
    }
}
