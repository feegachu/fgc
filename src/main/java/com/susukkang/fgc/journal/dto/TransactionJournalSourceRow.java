package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * journalPostingStep(Step 6a)이 commission_transaction에서 읽는 실제 분개(ACTUAL_INSURER_STATEMENT /
 * CONFIRMED_FC_PAYOUT) 원천 1행. contractId는 commission_transaction.source_contract_id가
 * 아니라 transaction_attribution.contract_id(attribution_scope='CONTRACT')에서 가져온다
 * — source_contract_id는 GA_MANUAL_PAYMENT 원천만 채워져 다른 원천에서는 항상 NULL이다
 * (JournalPostingSourceMapper.xml 주석 참고, 코드리뷰로 발견).
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
