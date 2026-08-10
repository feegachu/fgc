package com.susukkang.fgc.validation.batch;

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

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MonthlyValidationJobExecutionListenerTest {

    @Mock
    private ValidationRunBatchLifecycleService lifecycleService;

    private static MonthlyValidationJobParameters parameters() {
        return new MonthlyValidationJobParameters(
                java.time.LocalDate.of(2026, 8, 1), 1L,
                com.susukkang.fgc.common.code.ValidationRunType.MONTHLY, 12L, "request-1");
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
    void completedJobCompletesTheRun() {
        MonthlyValidationJobExecutionListener listener = new MonthlyValidationJobExecutionListener(lifecycleService);
        JobExecution jobExecution = jobExecution(BatchStatus.COMPLETED);
        jobExecution.getExecutionContext().putLong("validationRunId", 100L);

        listener.afterJob(jobExecution);

        verify(lifecycleService).complete(100L, parameters());
    }

    @Test
    void failedJobDoesNotCallCompleteRun() {
        MonthlyValidationJobExecutionListener listener = new MonthlyValidationJobExecutionListener(lifecycleService);
        JobExecution jobExecution = jobExecution(BatchStatus.FAILED);
        jobExecution.getExecutionContext().putLong("validationRunId", 100L);

        listener.afterJob(jobExecution);

        verify(lifecycleService, never()).complete(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void completedJobWithoutValidationRunIdDoesNothing() {
        MonthlyValidationJobExecutionListener listener = new MonthlyValidationJobExecutionListener(lifecycleService);
        JobExecution jobExecution = jobExecution(BatchStatus.COMPLETED);

        listener.afterJob(jobExecution);

        verify(lifecycleService, never()).complete(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
    }
}
