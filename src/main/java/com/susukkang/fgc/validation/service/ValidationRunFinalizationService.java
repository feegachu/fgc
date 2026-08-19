package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.FinalizeChecklistResponse;

public interface ValidationRunFinalizationService {

    FinalizeChecklistResponse getChecklist(Long validationRunId);
}
