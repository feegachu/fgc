package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceCheckPort;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceDetectedException;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;

/**
 * #98 이슈 To-do "불균형 1건 이상 시 배치 Step 실패 및 실행 상태 실패 전이 테스트".
 *
 * validation_run이 실제로 FAILED로 전이되는지는 이 테스트의 범위가 아니다 — 그건
 * ValidationRunStepProgressListener가 Step 실패(예외)를 받아서 하는 일이고, 이미
 * CreateDailyRunTaskletTest류가 검증하는 다른 Step들과 같은 공통 경로다. 여기서는
 * "imbalanceCount > 0이면 이 Tasklet이 Step을 실패시킬 예외를 던지는지"만 검증한다 —
 * 그 예외가 일단 나면 나머지는 이미 검증된 공통 경로를 그대로 탄다.
 */
@ExtendWith(MockitoExtension.class)
class LedgerImbalanceCheckTaskletTest {

    @Mock
    private LedgerImbalanceCheckPort ledgerImbalanceCheckPort;

    private ChunkContext newChunkContext(Long validationRunId) {
        JobParameters parameters = new JobParametersBuilder()
                .addString("validationMonth", "2026-08")
                .addLong("runNo", 1L)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 1L)
                .addString("requestId", "req-98")
                .toJobParameters();
        JobExecution jobExecution = new JobExecution(new JobInstance(1L, "MonthlyValidationJob"), parameters);
        StepExecution stepExecution = new StepExecution("imbalanceCheckStep", jobExecution);
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));
        if (validationRunId != null) {
            ValidationRunBatchContext.putValidationRunId(chunkContext, validationRunId);
        }
        return chunkContext;
    }

    @Test
    void noImbalanceFinishesTheStepSuccessfully() {
        given(ledgerImbalanceCheckPort.check(any(ValidationStepContext.class)))
                .willReturn(new LedgerImbalanceResult(10, 0));

        LedgerImbalanceCheckTasklet tasklet = new LedgerImbalanceCheckTasklet(ledgerImbalanceCheckPort);
        RepeatStatus result = tasklet.execute(null, newChunkContext(42L));

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
    }

    @Test
    void oneOrMoreImbalancesFailTheStep() {
        given(ledgerImbalanceCheckPort.check(any(ValidationStepContext.class)))
                .willReturn(new LedgerImbalanceResult(10, 3));

        LedgerImbalanceCheckTasklet tasklet = new LedgerImbalanceCheckTasklet(ledgerImbalanceCheckPort);

        assertThatThrownBy(() -> tasklet.execute(null, newChunkContext(42L)))
                .isInstanceOf(LedgerImbalanceDetectedException.class)
                .hasMessageContaining("3");
    }

    @Test
    void checkIsCalledWithTheValidationRunIdFromJobExecutionContext() {
        given(ledgerImbalanceCheckPort.check(any(ValidationStepContext.class)))
                .willReturn(new LedgerImbalanceResult(1, 0));

        LedgerImbalanceCheckTasklet tasklet = new LedgerImbalanceCheckTasklet(ledgerImbalanceCheckPort);
        tasklet.execute(null, newChunkContext(99L));

        org.mockito.ArgumentCaptor<ValidationStepContext> captor =
                org.mockito.ArgumentCaptor.forClass(ValidationStepContext.class);
        org.mockito.Mockito.verify(ledgerImbalanceCheckPort).check(captor.capture());
        assertThat(captor.getValue().validationRunId()).isEqualTo(99L);
        assertThat(captor.getValue().job().requestId()).isEqualTo("req-98");
    }
}
