package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * validation_run 1행 조회 결과
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
