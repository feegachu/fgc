package com.susukkang.fgc.journal.domain;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * ACTUAL_INSURER_STATEMENT(원수사 실제 지급명세) 분개 초안 생성 명령.
 *
 * source_entity_type은 항상 "COMMISSION_TRANSACTION", source_entity_id는
 * commissionTransactionId다 (commission_transaction 중 payment_stage=INSURER_TO_GA,
 * source_type=INSURER_STATEMENT 건, 운영정책서 제38조). GA 레벨 원장이라
 * journal_line.agent_id는 채우지 않는다.
 */
@Getter
@Builder
public class ActualInsurerStatementJournalCommand {
    private final Long commissionTransactionId;
    private final Long contractId;
    private final Long policyVersionId;
    private final Long validationRunId;
    private final Long commissionItemId;
    private final LocalDate journalDate;
    private final BigDecimal actualAmount;
    private final String description;
}
