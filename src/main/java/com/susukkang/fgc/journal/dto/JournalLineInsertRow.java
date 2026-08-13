package com.susukkang.fgc.journal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * journal_line 1행 INSERT 파라미터.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JournalLineInsertRow {
    private Long journalLineId;
    private Long journalHeaderId;
    private Integer lineNo;
    private Long journalAccountId;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private Long contractId;
    private Long agentId;
    private String paymentStage;
    private Long commissionItemId;
    private String memo;
}
