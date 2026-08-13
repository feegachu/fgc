package com.susukkang.fgc.journal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * journal_header 1행 INSERT 파라미터
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JournalHeaderInsertRow {
    private Long journalHeaderId;
    private String journalNo;
    private LocalDate journalDate;
    private String journalType;
    private String sourceEntityType;
    private String sourceEntityId;
    private Integer revisionNo;
    private Long validationRunId;
    private Long contractId;
    private Long policyVersionId;
    private String correctionGroupKey;
    private String description;
    private Long createdBy;
}
