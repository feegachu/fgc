package com.susukkang.fgc.journal.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDate;

/**
 * journal_header 1행 INSERT 파라미터(#93). status/created_at은 DB 기본값(DRAFT,
 * clock_timestamp())에 맡기고 여기 담지 않는다 — guard_journal_header_write 트리거가
 * INSERT 시 status<>'DRAFT'면 거부하므로(V1__baseline_v2_1_2.sql:1296-1299), 굳이 앱이
 * 값을 정해 보낼 이유가 없다.
 *
 * journalHeaderId는 MyBatis useGeneratedKeys로 INSERT 후 채워진다(ValidationRunInsertRow와
 * 같은 관례) — journal_line INSERT 시 FK로 필요하기 때문에 setter가 있다.
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
