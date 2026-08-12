package com.susukkang.fgc.journal.domain;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CONFIRMED_FC_PAYOUT(GA 확정 지급 건) 분개 초안 생성 명령.
 *
 * source_entity_type은 항상 "COMMISSION_TRANSACTION", source_entity_id는
 * commissionTransactionId다 (commission_transaction 중 payment_stage=GA_TO_FC,
 * status=CONFIRMED 건, 운영정책서 제38조). 특정 설계사에게 귀속되는 지급이므로
 * journal_line.agent_id에 beneficiaryAgentId를 채운다.
 */
@Getter
@Builder
public class ConfirmedFcPayoutJournalCommand {
    private final Long commissionTransactionId;
    private final Long contractId;
    private final Long policyVersionId;
    private final Long validationRunId;
    private final Long beneficiaryAgentId;
    private final Long commissionItemId;
    private final LocalDate journalDate;
    private final BigDecimal confirmedAmount;
    private final String description;
}
