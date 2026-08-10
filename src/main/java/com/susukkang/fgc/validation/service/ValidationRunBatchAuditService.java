package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;

/** MonthlyValidationJob의 업무 상태 전이를 audit_log에 남긴다. */
public interface ValidationRunBatchAuditService {

    void recordStarted(Long validationRunId, MonthlyValidationJobParameters parameters);

    void recordCompleted(Long validationRunId, MonthlyValidationJobParameters parameters);

    void recordFailed(Long validationRunId, MonthlyValidationJobParameters parameters, String failureMessage);
}
