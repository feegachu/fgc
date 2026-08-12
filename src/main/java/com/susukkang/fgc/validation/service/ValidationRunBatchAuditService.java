package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;

/** MonthlyValidationJob의 업무 상태 전이를 audit_log에 남긴다. */
public interface ValidationRunBatchAuditService {

    void recordStarted(Long validationRunId, MonthlyValidationJobParameters parameters);

    void recordCompleted(Long validationRunId, MonthlyValidationJobParameters parameters);

    void recordStepAdvanced(Long validationRunId, MonthlyValidationJobParameters parameters, int step);

    void recordFailed(Long validationRunId, MonthlyValidationJobParameters parameters, String failureMessage);

    void recordRunCreationFailed(Long jobExecutionId, MonthlyValidationJobParameters parameters, String failureMessage);

    /**
     * FAILED였던 실행을 RUNNING으로 되돌려 재시도할 때 남김
     * 이 전이는 lifecycleService.start()를 타지 않고 범용 ValidationRunTransitionService를 쓰기
     * 때문에, 그 호출자가 성공한 뒤 이 메서드를 직접 불러야 한다.
     */
    void recordRetried(Long validationRunId, MonthlyValidationJobParameters parameters);
}
