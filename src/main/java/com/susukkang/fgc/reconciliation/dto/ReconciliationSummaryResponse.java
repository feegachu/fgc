package com.susukkang.fgc.reconciliation.dto;

import java.math.BigDecimal;

/** IF-API-40 대사 실행 집계 응답. */
public record ReconciliationSummaryResponse(
        long resultCount,
        long matchedCount,
        long exceptionCount,
        BigDecimal expectedTotal,
        BigDecimal actualTotal,
        BigDecimal differenceTotal
) {
    public static ReconciliationSummaryResponse from(ReconciliationSummaryRow row) {
        return new ReconciliationSummaryResponse(
                row.getResultCount(), row.getMatchedCount(), row.getExceptionCount(),
                row.getExpectedTotal(), row.getActualTotal(), row.getDifferenceTotal());
    }
}
