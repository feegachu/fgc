package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 설명 : 검증 배치의 상태 전이를 감사로그로 기록하고 저장 실패를 호출자에게 전파한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@Service
@RequiredArgsConstructor
public class ValidationRunBatchAuditServiceImpl implements ValidationRunBatchAuditService {

    private final AuditLogRepository auditLogRepository;

    @Override
    public void recordStarted(Long validationRunId, MonthlyValidationJobParameters parameters) {
        record("VALIDATION_RUN_STARTED", "VALIDATION_RUN", validationRunId, parameters, "MonthlyValidationJob started");
    }

    @Override
    public void recordCompleted(Long validationRunId, MonthlyValidationJobParameters parameters) {
        record("VALIDATION_RUN_COMPLETED", "VALIDATION_RUN", validationRunId, parameters, "MonthlyValidationJob completed");
    }

    @Override
    public void recordStepAdvanced(Long validationRunId, MonthlyValidationJobParameters parameters, int step) {
        record("VALIDATION_RUN_STEP_ADVANCED", "VALIDATION_RUN", validationRunId, parameters,
                "MonthlyValidationJob advanced to step " + step);
    }

    @Override
    public void recordFailed(Long validationRunId, MonthlyValidationJobParameters parameters, String failureMessage) {
        record("VALIDATION_RUN_FAILED", "VALIDATION_RUN", validationRunId, parameters, failureMessage);
    }

    @Override
    public void recordRunCreationFailed(Long jobExecutionId, MonthlyValidationJobParameters parameters, String failureMessage) {
        record("VALIDATION_RUN_CREATION_FAILED", "BATCH_JOB_EXECUTION", jobExecutionId, parameters, failureMessage);
    }

    @Override
    public void recordRetried(Long validationRunId, MonthlyValidationJobParameters parameters) {
        record("VALIDATION_RUN_RETRIED", "VALIDATION_RUN", validationRunId, parameters,
                "FAILED validation_run retried (status reverted to RUNNING)");
    }

    /**
     * 설명 : 배치 실행자와 요청 식별자를 담은 감사 엔티티를 생성해 호출자의 트랜잭션에서 저장한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-27
     */
    private void record(String actionCode, String entityType, Long entityId,
                        MonthlyValidationJobParameters parameters, String reason) {
        // 2026-09-27 hjKang - 배치 감사 저장을 JPA Repository로 전환한다.
        // 기존 코드: MyBatis 저장 DTO와 INSERT 영향 행 수로 저장 결과를 확인했다.
        // 문제: 배치 감사 경로만 별도의 XML 쿼리에 의존했다.
        // 개선: 길이 보정과 배치 문맥을 유지하고 saveAndFlush 오류를 전파해 상태 변경도 함께 롤백한다.
        AuditLog auditLog = AuditLog.create(parameters.triggeredBy(), actionCode, entityType,
                String.valueOf(entityId), null, null, limit(reason, 1000),
                limit(parameters.requestId(), 80), null, null);
        auditLogRepository.saveAndFlush(auditLog);
    }

    private static String limit(String value, int length) {
        return value == null || value.length() <= length ? value : value.substring(0, length);
    }
}
