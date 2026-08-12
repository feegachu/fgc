package com.susukkang.fgc.journal.domain;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * EXPECTED_INSURER_INCOME(원수사→GA 예상 수입) 분개 초안 생성 명령.
 *
 * source_entity_type은 항상 "SCHEDULE_LINE", source_entity_id는 scheduleLineId다
 * (schedule_header/schedule_line 중 원수사→GA 방향 회차, 운영정책서 제38조).
 * GA 레벨 원장이라 journal_line.agent_id는 채우지 않는다 — 특정 설계사에 귀속되지 않는다.
 */
@Getter
@Builder
public class ExpectedInsurerIncomeJournalCommand {
    private final Long scheduleLineId;
    private final Long contractId;
    private final Long policyVersionId;
    private final Long validationRunId;
    private final Long commissionItemId;
    private final LocalDate journalDate;
    private final BigDecimal expectedAmount;
    private final String description;
}
