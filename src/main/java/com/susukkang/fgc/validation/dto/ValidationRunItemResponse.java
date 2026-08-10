package com.susukkang.fgc.validation.dto;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.code.ValidationRunType;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 검증 실행 목록 1건의 API 응답
 * currentStep은 validation_run.current_step(10단계 중 진행 위치, 0~10) 그대로 노출
 */
public record ValidationRunItemResponse(
        Long validationRunId,
        LocalDate validationMonth,
        Integer runNo,
        ValidationRunType runType,
        String runTypeLabel,
        ValidationRunStatus status,
        String statusLabel,
        Integer currentStep,
        String triggeredBy,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        String finalizedBy,
        OffsetDateTime finalizedAt,
        String failureMessage
) {
    public static ValidationRunItemResponse from(ValidationRunListRow row) {
        ValidationRunType runType = ValidationRunType.valueOf(row.getRunType());
        ValidationRunStatus status = ValidationRunStatus.valueOf(row.getStatus());
        return new ValidationRunItemResponse(
                row.getValidationRunId(), row.getValidationMonth(), row.getRunNo(),
                runType, runType.label(), status, status.label(), row.getCurrentStep(),
                row.getTriggeredBy(), row.getStartedAt(), row.getCompletedAt(),
                row.getFinalizedBy(), row.getFinalizedAt(), row.getFailureMessage());
    }
}
