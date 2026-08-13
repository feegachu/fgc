package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * journal_header 1행 조회 결과
 * saveDraft()의 반환값이자, 원천·개정번호 기준 중복 저장 방지를 위한 기존 행 조회(findBySourceKey)에도 씀
 */
@Getter
@Setter
public class JournalHeaderRow {
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
    private String status;
    private String description;
    private Long createdBy;
    private Long postedBy;
    private OffsetDateTime postedAt;
    private OffsetDateTime createdAt;
}
