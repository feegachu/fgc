package com.susukkang.fgc.validation.dto;

import com.susukkang.fgc.common.code.ValidationRunStatus;

/**
 * IF-API-48 실행 요청 202 응답.
 * status는 스펙 리터럴 "RUNNING" 고정이다(§4-2) — 배치는 비동기라 DB는 아직 CREATED일 수
 * 있지만, 202는 "수락됨"의 약속이고 화면 진실은 직후 IF-API-49 폴링이 DB에서 읽는다.
 */
public record ValidationRunExecuteResponse(
        Long validationRunId,
        String status,
        String statusLabel,
        Integer currentStep,
        Integer totalSteps
) {
    private static final int TOTAL_STEPS = 10;

    public static ValidationRunExecuteResponse from(ValidationRunRow row) {
        return new ValidationRunExecuteResponse(
                row.getValidationRunId(),
                ValidationRunStatus.RUNNING.name(),
                ValidationRunStatus.RUNNING.label(),
                row.getCurrentStep(),
                TOTAL_STEPS);
    }
}
