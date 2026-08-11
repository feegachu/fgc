package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.validation.service.ValidationRunBatchLifecycleService;
import com.susukkang.fgc.validation.service.ValidationRunBatchAuditService;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.BDDMockito.willThrow;
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
      
        verify(lifecycleService, never()).advance(anyLong(), anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void laterStepSuccessCallsAdvanceStepWithItsStepNo() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(3, false, lifecycleService, auditService);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterStep(stepExecution);

        verify(lifecycleService).advance(100L, 3, parameters());

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
        verify(lifecycleService, never()).advance(anyLong(), anyInt(), org.mockito.ArgumentMatchers.any());
        verify(lifecycleService, never()).fail(anyLong(), anyInt(), org.mockito.ArgumentMatchers.any(), anyString());
    }

    @Test
    // lifecycleService.advance가 예외를 던지면(예: VRUN_005 상태 충돌) afterStep이 그걸 삼키지
    // 않고 ExitStatus.FAILED로 바꿔 돌려줘야 Job이 그 자리에서 멈추고 다음 Step이 안 돈다
    // (실제로 예외가 그냥 던져지면 Spring Batch가 로그만 남기고 다음 Step을 계속 실행한다는
    // 것을 별도 스크래치 테스트로 재현 확인했다).
    void wrapsLifecycleServiceExceptionIntoFailedExitStatus() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(3, false, lifecycleService, auditService);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.COMPLETED);
        willThrow(new RuntimeException("db down")).given(lifecycleService).advance(100L, 3, parameters());

        ExitStatus result = listener.afterStep(stepExecution);

        assertThat(result).isEqualTo(ExitStatus.FAILED);
    }

    @Test
    // FGC-FUN-061: validation_run 생성 전 실패도 JobExecution ID로 감사 추적한다.
    void recordsCreationFailureAuditWithJobExecutionIdWhenInitialStepFailsBeforeRunIsCreated() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(1, true, lifecycleService, auditService);
        JobExecution jobExecution = new JobExecution(new JobInstance(7L, "MonthlyValidationJob"), validJobParameters());
        jobExecution.setId(42L);
        StepExecution stepExecution = new StepExecution("createRunStep", jobExecution);
        stepExecution.setStatus(BatchStatus.FAILED);
        stepExecution.addFailureException(new RuntimeException("duplicate monthly run"));

        listener.afterStep(stepExecution);

        verify(auditService).recordRunCreationFailed(org.mockito.ArgumentMatchers.eq(42L),
                org.mockito.ArgumentMatchers.eq(parameters()), contains("duplicate monthly run"));
    }

    private static JobParameters validJobParameters() {
        return new JobParametersBuilder().addString("validationMonth", "2026-08").addLong("runNo", 1L)
                .addString("runType", "MONTHLY").addLong("triggeredBy", 12L)
                .addString("requestId", "request-1").toJobParameters();
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
