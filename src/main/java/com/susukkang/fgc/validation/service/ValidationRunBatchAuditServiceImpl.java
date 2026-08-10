package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.audit.dto.AuditLogInsertRow;
import com.susukkang.fgc.audit.mapper.AuditLogMapper;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * api-spec.md 7-6 #5: 배치 감사로그는 JobParameter의 triggeredBy와 requestId를 그대로 쓴다.
 * 감사로그 기록 실패가 이미 완료된 배치 업무 트랜잭션을 되돌리지는 않는다. 기존 로그인 감사로그와
 * 같은 원칙으로 오류를 로그에 남기고, 업무 실행의 최종 상태는 보존한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ValidationRunBatchAuditServiceImpl implements ValidationRunBatchAuditService {

    private static final String ENTITY_TYPE = "VALIDATION_RUN";
    private static final int ENTITY_ID_MAX_LENGTH = 100;
    private static final int REQUEST_ID_MAX_LENGTH = 80;
    private static final int REASON_MAX_LENGTH = 1000;

    private final AuditLogMapper auditLogMapper;

    @Override
    public void recordStarted(Long validationRunId, MonthlyValidationJobParameters parameters) {
        record("VALIDATION_RUN_STARTED", validationRunId, parameters, "MonthlyValidationJob 시작");
    }

    @Override
    public void recordCompleted(Long validationRunId, MonthlyValidationJobParameters parameters) {
        record("VALIDATION_RUN_COMPLETED", validationRunId, parameters, "MonthlyValidationJob 완료");
    }

    @Override
    public void recordFailed(Long validationRunId, MonthlyValidationJobParameters parameters, String failureMessage) {
        record("VALIDATION_RUN_FAILED", validationRunId, parameters, failureMessage);
    }

    private void record(String actionCode, Long validationRunId, MonthlyValidationJobParameters parameters, String reason) {
        try {
            auditLogMapper.insert(AuditLogInsertRow.builder()
                    .userId(parameters.triggeredBy())
                    .actionCode(actionCode)
                    .entityType(ENTITY_TYPE)
                    .entityId(clamp(String.valueOf(validationRunId), ENTITY_ID_MAX_LENGTH))
                    .reason(clamp(reason, REASON_MAX_LENGTH))
                    .requestId(clamp(parameters.requestId(), REQUEST_ID_MAX_LENGTH))
                    .clientIp(null)
                    .build());
        } catch (RuntimeException e) {
            log.error("배치 감사로그 기록 실패 actionCode={} validationRunId={}",
                    actionCode, validationRunId, e);
        }
    }

    private static String clamp(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
