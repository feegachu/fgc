package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.journal.domain.JournalType;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDate;
import java.util.List;

/**
 * journal_header + journal_line 초안
 */
@Getter
@Builder(toBuilder = true)
public class JournalHeaderDraft {
    private final JournalType journalType;
    private final LocalDate journalDate;
    private final String sourceEntityType;
    private final String sourceEntityId;
    private final int revisionNo;
    private final Long validationRunId;
    private final Long contractId;
    private final Long policyVersionId;
    private final String description;
    private final List<JournalLineDraft> lines;
}
