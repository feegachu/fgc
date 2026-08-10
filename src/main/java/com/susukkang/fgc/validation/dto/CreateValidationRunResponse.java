package com.susukkang.fgc.validation.dto;

/**
 * POST /api/v1/validation-runs 응답 본문.
 * api-spec.md IF-API-45 규격: validationRunId, runNo, status:"CREATED".
 */
public record CreateValidationRunResponse(
        Long validationRunId,
        Integer runNo,
        String status
) {
    public static CreateValidationRunResponse from(ValidationRunRow row) {
        return new CreateValidationRunResponse(row.getValidationRunId(), row.getRunNo(), row.getStatus());
    }
}
