package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** vw_reconciliation_summary 조회 모델. */
@Getter
@Setter
public class ReconciliationSummaryRow {
    private long resultCount;
    private long matchedCount;
    private long exceptionCount;
    private BigDecimal expectedTotal;
    private BigDecimal actualTotal;
    private BigDecimal differenceTotal;
}
