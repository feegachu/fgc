package com.susukkang.fgc.journal.domain;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * EXPECTED_FC_PAYOUT(GA→설계사 예상 지급) 분개 초안 생성 명령.
 *
 * source_entity_type은 항상 "SCHEDULE_LINE", source_entity_id는 scheduleLineId다
 * (schedule_header/schedule_line 중 GA→설계사 방향 회차, 운영정책서 제38조).
 * 특정 설계사에게 귀속되는 지급이므로 journal_line.agent_id에 beneficiaryAgentId를 채운다.
 */
@Getter
@Builder
public class ExpectedFcPayoutJournalCommand {
    private final Long scheduleLineId;
    private final Long contractId;
    private final Long policyVersionId;
    private final Long validationRunId;
    private final Long beneficiaryAgentId;
    private final Long commissionItemId;
    private final LocalDate journalDate;
    private final BigDecimal expectedAmount;
    private final String description;
}
