package com.susukkang.fgc.reconciliation.dto;

import java.math.BigDecimal;

/**
 * 설명 : reconciliation_match와 상세 snapshot에 저장할 예상·실제 원자행
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
public record ReconciliationMatchSource(
        Long scheduleLineId,
        Long transactionAttributionId,
        Long journalHeaderId,
        BigDecimal matchedAmount,
        String matchRole
) {
}
