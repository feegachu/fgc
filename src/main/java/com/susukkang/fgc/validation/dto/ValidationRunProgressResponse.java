package com.susukkang.fgc.validation.dto;

import com.susukkang.fgc.common.code.ValidationRunStatus;

/**
 * IF-API-49 진행률 폴링 응답.
 * 진행률은 validation_run.current_step만 읽는다 — batch_step_execution에서 역산하지
 * 않는다(화면정의서 VRUN-W02). progressPct = current_step / 10 × 100 (운영정책서 제43조).
 */
public record ValidationRunProgressResponse(
        String status,
        String statusLabel,
        Integer currentStep,
        Integer totalSteps,
        Integer progressPct,
        Integer failedStep,
        String failureMessage
) {
    private static final int TOTAL_STEPS = 10;

    public static ValidationRunProgressResponse from(ValidationRunRow row) {
        ValidationRunStatus status = ValidationRunStatus.valueOf(row.getStatus());
        return new ValidationRunProgressResponse(
                status.name(),
                status.label(),
                row.getCurrentStep(),
                TOTAL_STEPS,
                row.getCurrentStep() * 100 / TOTAL_STEPS,
                status == ValidationRunStatus.FAILED ? row.getCurrentStep() : null,
                row.getFailureMessage());
    }
}
