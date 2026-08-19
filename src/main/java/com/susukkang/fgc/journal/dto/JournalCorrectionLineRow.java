package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** 역분개 라인 생성을 위한 원분개 라인 조회 결과. */
@Getter
@Setter
public class JournalCorrectionLineRow {
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
