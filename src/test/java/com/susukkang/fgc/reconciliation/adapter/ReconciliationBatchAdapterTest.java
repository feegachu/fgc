package com.susukkang.fgc.reconciliation.adapter;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.service.ReconciliationBatchRunService;
import com.susukkang.fgc.reconciliation.service.ReconciliationExecutionCoordinator;
import com.susukkang.fgc.reconciliation.service.ReconciliationFailureRecorder;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

/**
 * 설명 : Step 7 대사 어댑터의 처리건수와 치명적 실패 종결 테스트
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationBatchAdapterTest {

    @Mock
    private ReconciliationBatchRunService batchRunService;

    @Mock
    private ReconciliationExecutionCoordinator executionCoordinator;

    @Mock
    private ReconciliationFailureRecorder failureRecorder;

    private ReconciliationBatchAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new ReconciliationBatchAdapter(
                batchRunService, executionCoordinator, failureRecorder);
    }

    @Test
    void 보험회사별_처리건수를_합산한다() {
        ValidationStepContext context = context();
        ReconciliationExecutionRequest first = request(11L, 101L);
        ReconciliationExecutionRequest second = request(12L, 102L);
        given(batchRunService.prepare(context, PaymentStage.GA_TO_FC))
                .willReturn(List.of(first, second));
        given(executionCoordinator.execute(first)).willReturn(2L);
        given(executionCoordinator.execute(second)).willReturn(3L);

        StepProcessingResult result = adapter.reconcile(context, PaymentStage.GA_TO_FC);

        assertThat(result.processedCount()).isEqualTo(5);
        assertThat(result.failureCount()).isZero();
    }

    @Test
    void 치명적_오류로_Step이_중단되면_미실행_RUN도_FAILED로_종결한다() {
        ValidationStepContext context = context();
        ReconciliationExecutionRequest first = request(11L, 101L);
        ReconciliationExecutionRequest failed = request(12L, 102L);
        ReconciliationExecutionRequest pending = request(13L, 103L);
        given(batchRunService.prepare(context, PaymentStage.GA_TO_FC))
                .willReturn(List.of(first, failed, pending));
        given(executionCoordinator.execute(first)).willReturn(1L);
        given(executionCoordinator.execute(failed))
                .willThrow(new IllegalStateException("치명적 오류"));

        assertThatThrownBy(() -> adapter.reconcile(context, PaymentStage.GA_TO_FC))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("치명적 오류");

        verify(failureRecorder).record(pending.reconciliationRunId());
    }

    private static ValidationStepContext context() {
        return new ValidationStepContext(
                77L,
                new ValidationJobContext(
                        LocalDate.of(2026, 8, 1),
                        1,
                        ValidationRunType.MONTHLY,
                        5,
                        "IT-048-04"
                )
        );
    }

    private static ReconciliationExecutionRequest request(Long runId, Long insurerId) {
        return new ReconciliationExecutionRequest(
                runId,
                77L,
                LocalDate.of(2026, 8, 1),
                PaymentStage.GA_TO_FC,
                insurerId,
                5L
        );
    }
}
