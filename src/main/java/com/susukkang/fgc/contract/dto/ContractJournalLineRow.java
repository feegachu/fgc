package com.susukkang.fgc.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 계약 기준 journal_header + journal_line + journal_account 조인 조회 결과 1행.
 * 라인 하나당 한 행이라, 같은 journalHeaderId를 가진 행이 보통 2개(차변 1·대변 1)씩 묶여서 나옴
 */
@Getter
@Setter
public class ContractJournalLineRow {
    private Long journalHeaderId;
    private String journalNo;
    private LocalDate journalDate;
    private String journalType;
    private String sourceEntityType;
    private String sourceEntityId;
    private Integer revisionNo;
    private Long policyVersionId;
    private Long validationRunId;
    private String status;
    private String description;
    private Integer lineNo;
    private String accountCode;
    private String accountName;
    private BigDecimal debitAmount;
    private BigDecimal creditAmount;
    private Long agentId;
    private String paymentStage;
    private Long commissionItemId;
    private String memo;
}
