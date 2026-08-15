package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * journalPostingStep(Step 6a)이 commission_transaction에서 읽는 실제 분개(ACTUAL_INSURER_STATEMENT /
 * CONFIRMED_FC_PAYOUT) 원천 1행. contractId는 commission_transaction.source_contract_id
 * (V9__commission_payment_natural_key.sql이 정의한 자연키 계약)에서 그대로 가져온다.
 */
@Getter
@Setter
public class TransactionJournalSourceRow {
    private Long commissionTransactionId;
    private Long contractId;
    private Long policyVersionId;
    private Long commissionItemId;
    private Long beneficiaryAgentId;
    private LocalDate journalDate;
    private BigDecimal amount;
    private String description;
}
