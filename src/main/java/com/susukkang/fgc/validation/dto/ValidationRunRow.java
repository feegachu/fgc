package com.susukkang.fgc.validation.dto;

import com.susukkang.fgc.validation.entity.ValidationRun;
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
    private Integer currentStep;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime finalizedAt;
    private Long triggeredBy;
    private Long finalizedBy;
    private String failureMessage;
    private OffsetDateTime createdAt;

    /** ValidationRun 엔티티를 응답용 Row로 옮긴다. 필드가 늘면 여기 한 곳만 고치면 된다. */
    public static ValidationRunRow from(ValidationRun run) {
        ValidationRunRow row = new ValidationRunRow();
        row.setValidationRunId(run.getValidationRunId());
        row.setValidationMonth(run.getValidationMonth());
        row.setRunNo(run.getRunNo());
        row.setRunType(run.getRunType());
        row.setStatus(run.getStatus().name());
        row.setCurrentStep(run.getCurrentStep());
        row.setStartedAt(run.getStartedAt());
        row.setCompletedAt(run.getCompletedAt());
        row.setFinalizedAt(run.getFinalizedAt());
        row.setTriggeredBy(run.getTriggeredBy());
        row.setFinalizedBy(run.getFinalizedBy());
        row.setFailureMessage(run.getFailureMessage());
        row.setCreatedAt(run.getCreatedAt());
        return row;
    }
}
