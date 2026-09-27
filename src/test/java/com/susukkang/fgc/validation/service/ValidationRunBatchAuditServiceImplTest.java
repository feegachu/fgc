package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 설명 : 검증 배치 감사로그의 실행 문맥과 컬럼 길이 보정 및 저장 실패 전파를 검증한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-27
 */
@ExtendWith(MockitoExtension.class)
class ValidationRunBatchAuditServiceImplTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    private final MonthlyValidationJobParameters parameters = new MonthlyValidationJobParameters(
            LocalDate.of(2026, 8, 1), 7L, ValidationRunType.MONTHLY, 12L, "request-123");

    /**
     * 설명 : 배치 시작 시 실행자와 요청 식별자 및 감사 대상을 신규 엔티티에 담는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-27
     */
    @Test
    void recordsTriggeredByAndRequestIdWhenTheRunStarts() {
        ValidationRunBatchAuditService service = new ValidationRunBatchAuditServiceImpl(auditLogRepository);

        service.recordStarted(100L, parameters);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue())
                .extracting("auditLogId", "userId", "requestId", "actionCode", "entityType", "entityId", "clientIp")
                .containsExactly(null, 12L, "request-123", "VALIDATION_RUN_STARTED", "VALIDATION_RUN", "100", null);
    }

    /**
     * 설명 : 배치 실패 사유와 기존 실행 문맥이 감사 엔티티에 유지되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-27
     */
    @Test
    void recordsFailureReasonWithTheSameAuditContext() {
        ValidationRunBatchAuditService service = new ValidationRunBatchAuditServiceImpl(auditLogRepository);

        service.recordFailed(100L, parameters, "Step capCheckStep failed");

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue()).extracting("userId", "requestId", "actionCode", "reason")
                .containsExactly(12L, "request-123", "VALIDATION_RUN_FAILED", "Step capCheckStep failed");
    }

    /**
     * 설명 : #77 재시도 상태 전이 후 실행자와 요청 식별자가 동일한 감사 문맥으로 기록되는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-27
     */
    @Test
    void recordsRetryWithTheSameAuditContext() {
        ValidationRunBatchAuditService service = new ValidationRunBatchAuditServiceImpl(auditLogRepository);

        service.recordRetried(100L, parameters);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue()).extracting("userId", "requestId", "actionCode", "entityId")
                .containsExactly(12L, "request-123", "VALIDATION_RUN_RETRIED", "100");
    }

    /**
     * 설명 : 긴 실패 사유와 요청 식별자를 기존 컬럼 길이로 보정하는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-27
     */
    @Test
    void clampsFailureReasonAndRequestId() {
        var longParameters = new MonthlyValidationJobParameters(parameters.validationMonth(), parameters.runNo(),
                parameters.runType(), parameters.triggeredBy(), "r".repeat(81));
        var service = new ValidationRunBatchAuditServiceImpl(auditLogRepository);

        service.recordFailed(100L, longParameters, "x".repeat(1001));

        var captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());
        assertThat(captor.getValue()).extracting("reason", "requestId")
                .containsExactly("x".repeat(1000), "r".repeat(80));
    }

    /**
     * 설명 : 감사 저장 오류를 삼키지 않고 배치 상태 전이 호출자에게 전파하는지 검증한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-27
     */
    @Test
    void propagatesRepositoryFailure() {
        var failure = new DataIntegrityViolationException("audit unavailable");
        when(auditLogRepository.saveAndFlush(any())).thenThrow(failure);
        var service = new ValidationRunBatchAuditServiceImpl(auditLogRepository);

        assertThatThrownBy(() -> service.recordStarted(100L, parameters)).isSameAs(failure);
    }
}
