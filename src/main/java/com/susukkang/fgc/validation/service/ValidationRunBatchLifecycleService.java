package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;

public interface ValidationRunBatchLifecycleService {
    void start(Long validationRunId, MonthlyValidationJobParameters parameters);
    void advance(Long validationRunId, int step, MonthlyValidationJobParameters parameters);
    void complete(Long validationRunId, MonthlyValidationJobParameters parameters);
    void fail(Long validationRunId, int step, MonthlyValidationJobParameters parameters, String reason);
}
