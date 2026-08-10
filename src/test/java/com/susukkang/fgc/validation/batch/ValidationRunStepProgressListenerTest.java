package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.validation.service.ValidationRunBatchLifecycleService;
import com.susukkang.fgc.validation.service.ValidationRunBatchAuditService;
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
import org.springframework.batch.core.StepExecution;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ValidationRunStepProgressListenerTest {

    @Mock
    private ValidationRunBatchLifecycleService lifecycleService;

    @Mock
    private ValidationRunBatchAuditService auditService;

    private StepExecution stepExecutionWithRunId(long validationRunId) {
        JobParameters parameters = new JobParametersBuilder()
                .addString("validationMonth", "2026-08")
                .addLong("runNo", 1L)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 12L)
                .addString("requestId", "request-1")
                .toJobParameters();
        JobExecution jobExecution = new JobExecution(new JobInstance(1L, "MonthlyValidationJob"), parameters);
        jobExecution.getExecutionContext().putLong("validationRunId", validationRunId);
        StepExecution stepExecution = new StepExecution("step", jobExecution);
        return stepExecution;
    }

    @Test
    void initialStepSuccessCallsStartRunningNotAdvanceStep() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(1, true, lifecycleService, auditService);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterStep(stepExecution);

        verify(lifecycleService).start(100L, parameters());
        verify(lifecycleService, never()).advance(anyLong(), anyInt());
    }

    @Test
    void laterStepSuccessCallsAdvanceStepWithItsStepNo() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(3, false, lifecycleService, auditService);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterStep(stepExecution);

        verify(lifecycleService).advance(100L, 3);
        verify(lifecycleService, never()).start(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void stepFailureCallsMarkFailedWithStepNoAndExceptionMessage() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(5, false, lifecycleService, auditService);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.FAILED);
        stepExecution.addFailureException(new RuntimeException("boom"));

        listener.afterStep(stepExecution);

        verify(lifecycleService).fail(eq(100L), eq(5), org.mockito.ArgumentMatchers.eq(parameters()), contains("boom"));
    }

    @Test
    void doesNothingWhenValidationRunIdIsMissing() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(1, true, lifecycleService, auditService);
        JobExecution jobExecution = new JobExecution(new JobInstance(1L, "MonthlyValidationJob"), new JobParameters());
        StepExecution stepExecution = new StepExecution("step", jobExecution);
        stepExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterStep(stepExecution);

        verify(lifecycleService, never()).start(anyLong(), org.mockito.ArgumentMatchers.any());
        verify(lifecycleService, never()).advance(anyLong(), anyInt());
        verify(lifecycleService, never()).fail(anyLong(), anyInt(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    private static long eq(long value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static int eq(int value) {
        return org.mockito.ArgumentMatchers.eq(value);
    }

    private static MonthlyValidationJobParameters parameters() {
        return new MonthlyValidationJobParameters(
                java.time.LocalDate.of(2026, 8, 1), 1L,
                com.susukkang.fgc.common.code.ValidationRunType.MONTHLY, 12L, "request-1");
    }
}
