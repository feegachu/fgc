package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;

/** 정정 대상 원분개 잠금 조회 결과. */
@Getter
@Setter
public class JournalCorrectionHeaderRow {
    private Long journalHeaderId;
    private LocalDate journalDate;
    private String journalType;
    private String sourceEntityType;
    private String sourceEntityId;
    private Integer revisionNo;
    private Long validationRunId;
    private String validationRunStatus;
    private Long contractId;
    private Long policyVersionId;
    private String status;
    private String description;
}
