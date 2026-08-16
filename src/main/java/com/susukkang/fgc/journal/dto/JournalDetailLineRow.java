package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 검증원장 상세 분개 라인 1행.
 * journal_line + journal_account(계정과목) + agent(설계사명) + commission_item(수수료 항목명) 조인 투영.
 */
@Getter
@Setter
public class JournalDetailLineRow {
    private Integer lineNo;
    private String accountCode;
    private String accountName;
    private String normalBalance;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private Long contractId;
    private Long agentId;
    private String agentName;
    private String paymentStage;
    private Long commissionItemId;
    private String commissionItemName;
    private String memo;
}
