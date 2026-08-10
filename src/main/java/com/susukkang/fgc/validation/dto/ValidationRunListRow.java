package com.susukkang.fgc.validation.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 검증 실행 목록 1행. validation_run + app_user(실행자/확정자 login_id) 조인 투영
 */
@Getter
@Setter
public class ValidationRunListRow {
    private Long validationRunId;
    private LocalDate validationMonth;
    private Integer runNo;
    private String runType;
    private String status;
    private Integer currentStep;
    private String triggeredBy;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private String finalizedBy;
    private OffsetDateTime finalizedAt;
    private String failureMessage;
}
