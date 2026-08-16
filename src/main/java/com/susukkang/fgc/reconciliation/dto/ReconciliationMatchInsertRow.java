package com.susukkang.fgc.reconciliation.dto;

import java.math.BigDecimal;

/**
 * 설명 : reconciliation_match 멱등 저장 입력 모델
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
public record ReconciliationMatchInsertRow(
        Long reconciliationResultId,
        Integer matchSeq,
        Long scheduleLineId,
        Long transactionAttributionId,
        BigDecimal matchedAmount,
        String matchRole
) {
}
