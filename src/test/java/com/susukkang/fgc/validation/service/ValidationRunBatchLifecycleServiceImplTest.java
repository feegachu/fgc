package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * #75: ValidationRunBatchLifecycleServiceImpl은 진행상황(ValidationRunBatchProgressService)과
 * 감사로그(ValidationRunBatchAuditService)를 "한 트랜잭션 안에서 둘 다 하거나 둘 다 안 하거나"
 * 묶어주는 계층이다. 이 단위테스트는 두 협력자를 mock으로 대체해 "정상 흐름에서 어떤 순서로
 * 뭘 부르는지"와 "앞 단계(진행상황 갱신)가 실패하면 뒤 단계(감사로그)는 아예 안 불리는지"를
 * 검증한다. "실제로 롤백되는지"(진짜 트랜잭션 원자성)는 mock으로는 증명이 안 되므로
 * ValidationRunBatchLifecycleServiceImplIntegrationTest가 실제 DB로 별도 확인한다.
 */
@ExtendWith(MockitoExtension.class)
class ValidationRunBatchLifecycleServiceImplTest {

    @Mock
    private ValidationRunBatchProgressService progressService;

    @Mock
    private ValidationRunBatchAuditService auditService;

    private ValidationRunBatchLifecycleServiceImpl service() {
        return new ValidationRunBatchLifecycleServiceImpl(progressService, auditService);
    }

    private static MonthlyValidationJobParameters parameters() {
        return new MonthlyValidationJobParameters(
                LocalDate.of(2026, 8, 1), 1L, ValidationRunType.MONTHLY, 12L, "request-1");
    }

    @Test
    // start()는 "먼저 상태를 옮기고, 그다음 감사로그를 남기는" 순서여야 한다 — 반대로 하면
    // 감사로그에는 "시작했다"고 적혀 있는데 실제 상태는 아직 CREATED인 모순이 생길 수 있다.
    void startAdvancesProgressBeforeRecordingAudit() {
        ValidationRunBatchLifecycleServiceImpl service = service();

        service.start(100L, parameters());

        InOrder order = inOrder(progressService, auditService);
        order.verify(progressService).startRunning(100L);
        order.verify(auditService).recordStarted(100L, parameters());
    }

    @Test
    void advanceUpdatesProgressBeforeRecordingAudit() {
        ValidationRunBatchLifecycleServiceImpl service = service();

        service.advance(100L, 3, parameters());

        InOrder order = inOrder(progressService, auditService);
        order.verify(progressService).advanceStep(100L, 3);
        order.verify(auditService).recordStepAdvanced(100L, parameters(), 3);
    }

    @Test
    void completeUpdatesProgressBeforeRecordingAudit() {
        ValidationRunBatchLifecycleServiceImpl service = service();

        service.complete(100L, parameters());

        InOrder order = inOrder(progressService, auditService);
        order.verify(progressService).completeRun(100L);
        order.verify(auditService).recordCompleted(100L, parameters());
    }

    @Test
    void failUpdatesProgressBeforeRecordingAudit() {
        ValidationRunBatchLifecycleServiceImpl service = service();

        service.fail(100L, 5, parameters(), "boom");

        InOrder order = inOrder(progressService, auditService);
        order.verify(progressService).markFailed(100L, 5, "boom");
        order.verify(auditService).recordFailed(100L, parameters(), "boom");
    }

    @Test
    // progressService가 먼저 예외를 던지면 auditService는 아예 호출되지 않아야 한다 —
    // "상태 갱신 실패 + 감사로그만 성공"처럼 절반만 반영된 상태가 코드 레벨에서부터
    // 나오지 않게 막는다(실제 롤백 보장은 @Transactional + 통합테스트가 마저 증명한다).
    void doesNotRecordAuditWhenProgressUpdateFails() {
        ValidationRunBatchLifecycleServiceImpl service = service();
        willThrow(new RuntimeException("db down")).given(progressService).startRunning(100L);

        try {
            service.start(100L, parameters());
        } catch (RuntimeException expected) {
            // 예외 자체는 이 테스트의 관심사가 아니다 — 아래 verifyNoInteractions가 핵심.
        }

        verifyNoInteractions(auditService);
    }
}
