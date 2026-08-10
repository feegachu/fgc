package com.susukkang.fgc.validation.dto;

/**
 * POST /api/v1/validation-runs 요청 본문
 *
 * validationMonth("yyyy-MM")와 runType을 둘 다 String으로 받음
 */
public record CreateValidationRunRequest(
        String validationMonth,
        String runType
) {
}
