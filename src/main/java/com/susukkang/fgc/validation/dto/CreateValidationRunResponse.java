package com.susukkang.fgc.validation.dto;

/**
 * POST /api/v1/validation-runs 응답 본문
 */
public record CreateValidationRunResponse(
        Long validationRunId,
        String status
) {
    public static CreateValidationRunResponse from(ValidationRunRow row) {
        return new CreateValidationRunResponse(row.getValidationRunId(), row.getStatus());
    }
}
