package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceDetectedException;
import com.susukkang.fgc.validation.batch.contract.SkipLimitExceededException;
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
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ValidationRunStepProgressListenerTest {

    @Mock
    private ValidationRunBatchLifecycleService lifecycleService;

    @Mock
    private ValidationRunBatchAuditService auditService;

    @Mock
    private FgcMessageResolver messageResolver;

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
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(1, true, lifecycleService, auditService, messageResolver);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterStep(stepExecution);

        verify(lifecycleService).start(100L, parameters());
      
        verify(lifecycleService, never()).advance(anyLong(), anyInt(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void laterStepSuccessCallsAdvanceStepWithItsStepNo() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(3, false, lifecycleService, auditService, messageResolver);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterStep(stepExecution);

        verify(lifecycleService).advance(100L, 3, parameters());

        verify(lifecycleService, never()).start(anyLong(), org.mockito.ArgumentMatchers.any());
    }

    /** SRC-074: 예외 원문은 서버 로그에만 남기고 화면과 감사로그에는 요청번호를 안내한다. */
    @Test
    void stepFailureKeepsExceptionTextOutOfFailureMessage() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(5, false, lifecycleService, auditService, messageResolver);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.FAILED);
        stepExecution.addFailureException(new IllegalStateException("journal_no 월간 일련번호 상한(99999) 초과"));
        stepExecution.addFailureException(new RuntimeException("PreparedStatementCallback; SQL [INSERT INTO fgc.journal_header]"));

        listener.afterStep(stepExecution);

        verify(lifecycleService).fail(100L, 5, parameters(),
                "5단계 처리 중 오류가 발생했습니다. 요청번호 request-1를 담당자에게 알려주세요.");
    }

    @Test
    void knownFailuresTellTheUserWhatToDoNext() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(6, false, lifecycleService, auditService, messageResolver);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.FAILED);
        stepExecution.addFailureException(new LedgerImbalanceDetectedException(2));
        stepExecution.addFailureException(new SkipLimitExceededException("capCheckStep", PaymentStage.GA_TO_FC, 101, 100));

        listener.afterStep(stepExecution);

        verify(lifecycleService).fail(100L, 6, parameters(),
                "원장 불균형 2건이 발견되었습니다. 검증원장에서 확인한 뒤 실패 지점부터 재시작하세요."
                        + " | 처리하지 못한 계약 101건이 허용 한도 100건을 넘었습니다. 계약 자료를 확인한 뒤 실패 지점부터 재시작하세요.");
    }

    @Test
    void businessFailureUsesItsUserMessageInsteadOfTheCode() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(4, false, lifecycleService, auditService, messageResolver);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.FAILED);
        FgcBusinessException failure = new FgcBusinessException(FgcErrorCode.CAP_002);
        stepExecution.addFailureException(failure);
        given(messageResolver.resolve(FgcErrorCode.CAP_002, failure.getParams())).willReturn("적용 가능한 룰셋이 없습니다. 정책을 확인하세요.");

        listener.afterStep(stepExecution);

        verify(lifecycleService).fail(100L, 4, parameters(), "적용 가능한 룰셋이 없습니다. 정책을 확인하세요.");
    }

    @Test
    void internalBusinessFailureFallsBackToRequestIdSentence() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(4, false, lifecycleService, auditService, messageResolver);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.FAILED);
        stepExecution.addFailureException(new FgcBusinessException(FgcErrorCode.COMMON_500));

        listener.afterStep(stepExecution);

        verify(lifecycleService).fail(100L, 4, parameters(),
                "4단계 처리 중 오류가 발생했습니다. 요청번호 request-1를 담당자에게 알려주세요.");
    }

    /** SRC-074: 예외 메시지가 없으면 내부 Step 이름 대신 단계 번호로 실패를 알린다. */
    @Test
    void stepFailureWithoutMessageUsesStepNumberText() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(5, false, lifecycleService, auditService, messageResolver);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.FAILED);

        listener.afterStep(stepExecution);

        verify(lifecycleService).fail(100L, 5, parameters(), "5단계에서 실패했습니다.");
    }

    @Test
    void doesNothingWhenValidationRunIdIsMissing() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(1, true, lifecycleService, auditService, messageResolver);
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
    // 않고 Step의 BatchStatus를 FAILED로 올리고 ExitStatus.FAILED를 돌려줘야 한다.
    // ExitStatus만 FAILED면 SimpleJob은 BatchStatus(COMPLETED)만 보고 다음 Step을 계속 돌린다
    // (afterStep에서 예외를 그냥 던져도 AbstractStep이 로그만 남기고 삼킨다).
    void wrapsLifecycleServiceExceptionIntoFailedExitStatus() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(3, false, lifecycleService, auditService, messageResolver);
        StepExecution stepExecution = stepExecutionWithRunId(100L);
        stepExecution.setStatus(BatchStatus.COMPLETED);
        willThrow(new RuntimeException("db down")).given(lifecycleService).advance(100L, 3, parameters());

        ExitStatus result = listener.afterStep(stepExecution);

        assertThat(result).isEqualTo(ExitStatus.FAILED);
        assertThat(stepExecution.getStatus()).isEqualTo(BatchStatus.FAILED);
        assertThat(stepExecution.getFailureExceptions()).hasSize(1);
    }

    @Test
    // FGC-FUN-061: validation_run 생성 전 실패도 JobExecution ID로 감사 추적한다.
    void recordsCreationFailureAuditWithJobExecutionIdWhenInitialStepFailsBeforeRunIsCreated() {
        ValidationRunStepProgressListener listener = new ValidationRunStepProgressListener(1, true, lifecycleService, auditService, messageResolver);
        JobExecution jobExecution = new JobExecution(new JobInstance(7L, "MonthlyValidationJob"), validJobParameters());
        jobExecution.setId(42L);
        StepExecution stepExecution = new StepExecution("createRunStep", jobExecution);
        stepExecution.setStatus(BatchStatus.FAILED);
        stepExecution.addFailureException(new RuntimeException("duplicate monthly run"));

        listener.afterStep(stepExecution);

        verify(auditService).recordRunCreationFailed(42L, parameters(),
                "1단계 처리 중 오류가 발생했습니다. 요청번호 request-1를 담당자에게 알려주세요.");
    }

    private static JobParameters validJobParameters() {
        return new JobParametersBuilder().addString("validationMonth", "2026-08").addLong("runNo", 1L)
                .addString("runType", "MONTHLY").addLong("triggeredBy", 12L)
                .addString("requestId", "request-1").toJobParameters();
    }

    private static MonthlyValidationJobParameters parameters() {
        return new MonthlyValidationJobParameters(
                java.time.LocalDate.of(2026, 8, 1), 1L,
                com.susukkang.fgc.common.code.ValidationRunType.MONTHLY, 12L, "request-1");
    }
}
