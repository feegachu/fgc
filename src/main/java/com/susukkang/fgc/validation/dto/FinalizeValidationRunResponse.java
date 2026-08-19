package com.susukkang.fgc.validation.dto;

import java.time.OffsetDateTime;

/** POST /api/v1/validation-runs/{id}/finalize 응답(IF-API-51). */
public record FinalizeValidationRunResponse(
        String status,
        OffsetDateTime finalizedAt,
        String finalizedBy
) {
}
