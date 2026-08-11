package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ValidationRunBatchLifecycleServiceImpl implements ValidationRunBatchLifecycleService {
    private final ValidationRunBatchProgressService progressService;
    private final ValidationRunBatchAuditService auditService;

    @Override @Transactional
    public void start(Long id, MonthlyValidationJobParameters parameters) {
        progressService.startRunning(id);
        auditService.recordStarted(id, parameters);
    }

    @Override @Transactional
    public void advance(Long id, int step, MonthlyValidationJobParameters parameters) {
        progressService.advanceStep(id, step);
        auditService.recordStepAdvanced(id, parameters, step);

    }

    @Override @Transactional
    public void complete(Long id, MonthlyValidationJobParameters parameters) {
        progressService.completeRun(id);
        auditService.recordCompleted(id, parameters);
    }

    @Override @Transactional
    public void fail(Long id, int step, MonthlyValidationJobParameters parameters, String reason) {
        progressService.markFailed(id, step, reason);
        auditService.recordFailed(id, parameters, reason);
    }
}
