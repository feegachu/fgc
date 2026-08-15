package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * journalPostingStep(Step 6a)이 schedule_line에서 읽는 예상 분개(EXPECTED_INSURER_INCOME /
 * EXPECTED_FC_PAYOUT) 원천 1행. beneficiaryAgentId는 EXPECTED_FC_PAYOUT에서만 채워진다
 * (JournalPostingSourceMapper.xml 참고).
 */
@Getter
@Setter
public class ScheduleJournalSourceRow {
    private Long scheduleLineId;
    private Long contractId;
    private Long policyVersionId;
    private Long commissionItemId;
    private Long beneficiaryAgentId;
    private LocalDate journalDate;
    private BigDecimal amount;
    private String description;
}
