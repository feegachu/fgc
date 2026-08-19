package com.susukkang.fgc.validation.dto;

import java.util.List;

/** GET /api/v1/validation-runs/{id}/finalize-checklist 응답. */
public record FinalizeChecklistResponse(
        Long validationRunId,
        boolean passed,
        List<FinalizeChecklistConditionResponse> conditions
) {
    public FinalizeChecklistResponse {
        conditions = List.copyOf(conditions);
    }

    public long remainingConditionCount() {
        return conditions.stream().filter(condition -> !condition.passed()).count();
    }
}
