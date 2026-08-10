package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.audit.dto.AuditLogInsertRow;
import com.susukkang.fgc.audit.mapper.AuditLogMapper;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ValidationRunBatchAuditServiceImpl implements ValidationRunBatchAuditService {

    private final AuditLogMapper auditLogMapper;

    @Override
    public void recordStarted(Long validationRunId, MonthlyValidationJobParameters parameters) {
        record("VALIDATION_RUN_STARTED", "VALIDATION_RUN", validationRunId, parameters, "MonthlyValidationJob started");
    }

    @Override
    public void recordCompleted(Long validationRunId, MonthlyValidationJobParameters parameters) {
        record("VALIDATION_RUN_COMPLETED", "VALIDATION_RUN", validationRunId, parameters, "MonthlyValidationJob completed");
    }

    @Override
    public void recordFailed(Long validationRunId, MonthlyValidationJobParameters parameters, String failureMessage) {
        record("VALIDATION_RUN_FAILED", "VALIDATION_RUN", validationRunId, parameters, failureMessage);
    }

    @Override
    public void recordRunCreationFailed(Long jobExecutionId, MonthlyValidationJobParameters parameters, String failureMessage) {
        record("VALIDATION_RUN_CREATION_FAILED", "BATCH_JOB_EXECUTION", jobExecutionId, parameters, failureMessage);
    }

    private void record(String actionCode, String entityType, Long entityId,
                        MonthlyValidationJobParameters parameters, String reason) {
        int affected = auditLogMapper.insert(AuditLogInsertRow.builder()
                .userId(parameters.triggeredBy()).actionCode(actionCode).entityType(entityType)
                .entityId(String.valueOf(entityId)).reason(limit(reason, 1000))
                .requestId(limit(parameters.requestId(), 80)).clientIp(null).build());
        if (affected != 1) {
            throw new IllegalStateException("Batch audit log insert failed: " + actionCode);
        }
    }

    private static String limit(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
