package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.contract.ReconciliationBatchPort;
import com.susukkang.fgc.validation.service.ReconciliationSkipService;
import org.mockito.ArgumentCaptor;
import com.susukkang.fgc.validation.batch.contract.ContractSkip;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.common.code.PaymentStage;
import org.springframework.batch.repeat.RepeatStatus;
import java.util.List;
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

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * FGC-FUN-041: 대사 Step이 필수 실행 컨텍스트 누락을 명확하게 차단하는지 검증한다.
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@ExtendWith(MockitoExtension.class)
class ReconciliationTaskletTest {

    @Mock
    private ReconciliationBatchPort reconciliationBatchPort;
    @Mock
    private ReconciliationSkipService reconciliationSkipService;

    @Test
    void 양_지급단계의_처리와_skip_건수를_누적한다() {
        var context = newChunkContext();
        ValidationRunBatchContext.putValidationRunId(context, 42L);
        var contribution = context.getStepContext().getStepExecution().createStepContribution();
        given(reconciliationBatchPort.reconcile(any(), eq(PaymentStage.INSURER_TO_GA)))
                .willReturn(new StepProcessingResult(3, 2, 0, List.of(
                        new ContractSkip(1L, "RECONCILIATION_FAILED", "보험사 A 오류"),
                        new ContractSkip(2L, "RECONCILIATION_FAILED", "보험사 A 오류"))));
        given(reconciliationBatchPort.reconcile(any(), eq(PaymentStage.GA_TO_FC)))
                .willReturn(new StepProcessingResult(5, 1, 0, List.of(
                        new ContractSkip(3L, "RECONCILIATION_FAILED", "보험사 B 오류"))));

        assertThat(new ReconciliationTasklet(reconciliationBatchPort, reconciliationSkipService).execute(contribution, context))
                .isEqualTo(RepeatStatus.FINISHED);
        assertThat(contribution.getWriteCount()).isEqualTo(8);
        assertThat(contribution.getProcessSkipCount()).isEqualTo(3);
        verify(reconciliationBatchPort).reconcile(any(), eq(PaymentStage.INSURER_TO_GA));
        verify(reconciliationBatchPort).reconcile(any(), eq(PaymentStage.GA_TO_FC));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ContractSkip>> skips = ArgumentCaptor.forClass(List.class);
        verify(reconciliationSkipService).record(any(), eq(PaymentStage.INSURER_TO_GA), skips.capture());
        assertThat(skips.getValue()).containsExactly(
                new ContractSkip(1L, "RECONCILIATION_FAILED", "보험사 A 오류"),
                new ContractSkip(2L, "RECONCILIATION_FAILED", "보험사 A 오류"));
        verify(reconciliationSkipService).record(any(), eq(PaymentStage.GA_TO_FC), eq(List.of(
                new ContractSkip(3L, "RECONCILIATION_FAILED", "보험사 B 오류"))));
    }

    @Test
    void 치명적_오류는_후속_지급단계를_실행하지_않고_전파한다() {
        var context = newChunkContext();
        ValidationRunBatchContext.putValidationRunId(context, 42L);
        var contribution = context.getStepContext().getStepExecution().createStepContribution();
        var failure = new IllegalStateException("DB 연결 장애");
        given(reconciliationBatchPort.reconcile(any(), eq(PaymentStage.INSURER_TO_GA))).willThrow(failure);

        assertThatThrownBy(() -> new ReconciliationTasklet(reconciliationBatchPort, reconciliationSkipService).execute(contribution, context))
                .isSameAs(failure);
        verify(reconciliationBatchPort, never()).reconcile(any(), eq(PaymentStage.GA_TO_FC));
        verifyNoInteractions(reconciliationSkipService);
    }

    @Test
    void validationRunId가_없으면_대사를_호출하지_않고_즉시_실패한다() {
        ReconciliationTasklet tasklet = new ReconciliationTasklet(reconciliationBatchPort, reconciliationSkipService);

        assertThatThrownBy(() -> tasklet.execute(null, newChunkContext()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FGC-FUN-041")
                .hasMessageContaining("validationRunId");
        verifyNoInteractions(reconciliationBatchPort);
    }

    private static ChunkContext newChunkContext() {
        JobParameters parameters = new JobParametersBuilder()
                .addString("validationMonth", "2026-08")
                .addLong("runNo", 1L)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 1L)
                .addString("requestId", "IT-048-04-MISSING-CONTEXT")
                .toJobParameters();
        JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, "MonthlyValidationJob"), parameters);
        StepExecution stepExecution = new StepExecution("reconciliationStep", jobExecution);
        return new ChunkContext(new StepContext(stepExecution));
    }
}
