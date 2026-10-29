package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class ValidationRunBatchLifecycleServiceImpl implements ValidationRunBatchLifecycleService {
    private final ValidationRunRepository validationRunRepository;
    private final ValidationRunBatchProgressService progressService;
    private final ValidationRunBatchAuditService auditService;

    @Override @Transactional
    public void restart(Long id, MonthlyValidationJobParameters parameters) {
        if (parameters.restartFromExecutionId() != null) {
            validationRunRepository.lockValidationRunRestart(id);
            if (validationRunRepository.hasRestartClaim(id, parameters.restartFromExecutionId())) {
                throw new FgcBusinessException(FgcErrorCode.VRUN_005);
            }
        }
        if (validationRunRepository.restartFailed(id, ValidationRunStatus.FAILED, ValidationRunStatus.RUNNING) != 1) {
            throw new FgcBusinessException(FgcErrorCode.VRUN_005);
        }
        auditService.recordRetried(id, parameters);
    }

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
