package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.validation.service.ValidationRunBatchProgressService;
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
    private ValidationRunBatchProgressService progressService;

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
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(1, true, progressService, auditService);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterStep(stepExecution);

        verify(progressService).startRunning(100L);
        verify(auditService).recordStarted(100L, parameters());
        verify(progressService, never()).advanceStep(anyLong(), anyInt());
    }

    @Test
    void laterStepSuccessCallsAdvanceStepWithItsStepNo() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(3, false, progressService, auditService);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterStep(stepExecution);

        verify(progressService).advanceStep(100L, 3);
        verify(progressService, never()).startRunning(anyLong());
    }

    @Test
    void stepFailureCallsMarkFailedWithStepNoAndExceptionMessage() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(5, false, progressService, auditService);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.FAILED);
        stepExecution.addFailureException(new RuntimeException("boom"));

        listener.afterStep(stepExecution);

        verify(progressService).markFailed(eq(100L), eq(5), contains("boom"));
        verify(auditService).recordFailed(eq(100L), org.mockito.ArgumentMatchers.eq(parameters()), contains("boom"));
    }

    @Test
    void doesNothingWhenValidationRunIdIsMissing() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(1, true, progressService, auditService);
        JobExecution jobExecution = new JobExecution(new JobInstance(1L, "MonthlyValidationJob"), new JobParameters());
        StepExecution stepExecution = new StepExecution("step", jobExecution);
        stepExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterStep(stepExecution);

        verify(progressService, never()).startRunning(anyLong());
        verify(progressService, never()).advanceStep(anyLong(), anyInt());
        verify(progressService, never()).markFailed(anyLong(), anyInt(), anyString());
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
