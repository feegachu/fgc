package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;
import com.susukkang.fgc.validation.dto.FinalizeValidationRunResponse;

public interface ValidationRunFinalizationService {

    FinalizeChecklistResponse getChecklist(Long validationRunId);

    FinalizeValidationRunResponse finalizeRun(Long validationRunId, Long finalizedBy, String idempotencyKey);
}
