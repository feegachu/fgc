package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationRunCompletionGate;
import com.susukkang.fgc.validation.batch.contract.ValidationRunNotCompletableException;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.service.ValidationRunBatchLifecycleService;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * #75: Job이 COMPLETED로 끝났을 때 ValidationRunCompletionGate를 반드시 거친 뒤에만
 * lifecycleService.complete()가 불려야 한다는 계약을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class MonthlyValidationJobExecutionListenerTest {

    @Mock
    private ValidationRunBatchLifecycleService lifecycleService;

    @Mock
    private ValidationRunCompletionGate completionGate;

    private static MonthlyValidationJobParameters parameters() {
        return new MonthlyValidationJobParameters(
                java.time.LocalDate.of(2026, 8, 1), 1L,
                com.susukkang.fgc.common.code.ValidationRunType.MONTHLY, 12L, "request-1");
    }

    private static ValidationStepContext stepContext(long validationRunId) {
        return new ValidationStepContext(validationRunId, ValidationJobContext.from(parameters()));
    }

    private static JobExecution jobExecution(BatchStatus status) {
        JobParameters parameters = new JobParametersBuilder()
                .addString("validationMonth", "2026-08")
                .addLong("runNo", 1L)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 12L)
                .addString("requestId", "request-1")
                .toJobParameters();
        JobExecution execution = new JobExecution(new JobInstance(1L, "MonthlyValidationJob"), parameters);
        execution.setStatus(status);
        return execution;
    }

    @Test
    // 완료 조건 게이트를 통과하면(기본 NoOp이 그런 것처럼) validation_run을 COMPLETED로 전이한다.
    void completedJobPassesCompletionGateThenCompletesTheRun() {
        MonthlyValidationJobExecutionListener listener =
                new MonthlyValidationJobExecutionListener(lifecycleService, completionGate);
        JobExecution jobExecution = jobExecution(BatchStatus.COMPLETED);
        jobExecution.getExecutionContext().putLong("validationRunId", 100L);
        // completionGate.verifyCompletable(...)는 기본적으로 아무 것도 안 하는 mock이라
        // "통과"를 흉내낸다 — 예외를 던지도록 스텁하지 않으면 통과다.

        listener.afterJob(jobExecution);

        verify(completionGate).verifyCompletable(stepContext(100L));
        verify(lifecycleService).complete(100L, parameters());
    }

    @Test
    // 완료 조건 게이트가 막으면(ValidationRunNotCompletableException) complete()는 절대
    // 불리지 않고, 대신 fail()로 전이해야 한다 — "Step은 다 성공했지만 결과가 미완결"이라는
    // 사실을 화면에서 숨기지 않기 위해서다.
    void completionGateFailureFailsTheRunInsteadOfCompleting() {
        MonthlyValidationJobExecutionListener listener =
                new MonthlyValidationJobExecutionListener(lifecycleService, completionGate);
        JobExecution jobExecution = jobExecution(BatchStatus.COMPLETED);
        jobExecution.getExecutionContext().putLong("validationRunId", 100L);
        willThrow(new ValidationRunNotCompletableException("원장 불균형 2건"))
                .given(completionGate).verifyCompletable(stepContext(100L));

        listener.afterJob(jobExecution);

        verify(lifecycleService, never()).complete(anyLong(), any());
        verify(lifecycleService).fail(100L, 8, parameters(), "완료 조건 미충족: 원장 불균형 2건");
    }

    @Test
    void failedJobDoesNotCallCompleteRun() {
        MonthlyValidationJobExecutionListener listener =
                new MonthlyValidationJobExecutionListener(lifecycleService, completionGate);
        JobExecution jobExecution = jobExecution(BatchStatus.FAILED);
        jobExecution.getExecutionContext().putLong("validationRunId", 100L);

        listener.afterJob(jobExecution);

        verify(lifecycleService, never()).complete(anyLong(), any());
        // Step이 이미 실패한 경우엔 completionGate까지 갈 필요가 없다 — 애초에 안 부른다.
        verify(completionGate, never()).verifyCompletable(any());
    }

    @Test
    void completedJobWithoutValidationRunIdDoesNothing() {
        MonthlyValidationJobExecutionListener listener =
                new MonthlyValidationJobExecutionListener(lifecycleService, completionGate);
        JobExecution jobExecution = jobExecution(BatchStatus.COMPLETED);

        listener.afterJob(jobExecution);

        verify(lifecycleService, never()).complete(anyLong(), any());
        verify(completionGate, never()).verifyCompletable(any());
    }
}
