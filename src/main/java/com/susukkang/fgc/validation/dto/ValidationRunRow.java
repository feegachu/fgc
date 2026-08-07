package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * validation_run 1행 조회 결과.
 * 이 이슈(FUN-041 #1) 범위는 상태 전이 기반이라, INSERT/run_no 채번·policy_snapshot·
 * current_step 등은 다루지 않는다 — 상태 전이 판단에 필요한 컬럼만 옮겼다.
 */
@Getter
@Setter
public class ValidationRunRow {
    private Long validationRunId;
    private LocalDate validationMonth;
    private Integer runNo;
    private String runType;
    private String status;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime finalizedAt;
    private Long triggeredBy;
    private Long finalizedBy;
    private String failureMessage;
    private OffsetDateTime createdAt;
}
