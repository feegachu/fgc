package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

/**
 * 설명 : 대사 실행 실패와 FAILED 상태 기록 실패의 예외 보존을 검증한다.
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationExecutionCoordinatorTest {

    @Mock
    private ReconciliationExecutionService executionService;

    @Mock
    private ReconciliationFailureRecorder failureRecorder;

    private ReconciliationExecutionCoordinator coordinator;

    @BeforeEach
    void setUp() {
        coordinator = new ReconciliationExecutionCoordinator(executionService, failureRecorder);
    }

    @Test
    void FAILED_기록까지_실패하면_원래_실행오류에_suppressed로_보존한다() {
        ReconciliationExecutionRequest request = new ReconciliationExecutionRequest(
                11L, 77L, LocalDate.of(2026, 8, 1), PaymentStage.GA_TO_FC, 101L, 5L);
        IllegalStateException original = new IllegalStateException("실행 오류");
        given(executionService.execute(request)).willThrow(original);
        doThrow(new IllegalStateException("FAILED 기록 오류"))
                .when(failureRecorder).record(11L);

        assertThatThrownBy(() -> coordinator.execute(request))
                .isSameAs(original)
                .satisfies(exception -> assertThat(exception.getSuppressed())
                        .singleElement()
                        .isInstanceOfSatisfying(IllegalStateException.class,
                                suppressed -> assertThat(suppressed).hasMessage("FAILED 기록 오류")));
    }
}
