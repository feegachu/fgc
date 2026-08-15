package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * vw_journal_imbalance 조회 결과 1행
 */
@Getter
@Setter
public class LedgerImbalanceRow {
    private Long journalHeaderId;
    private String journalNo;
    private String status;
    private BigDecimal debitTotal;
    private BigDecimal creditTotal;
    private BigDecimal differenceAmount;
}
