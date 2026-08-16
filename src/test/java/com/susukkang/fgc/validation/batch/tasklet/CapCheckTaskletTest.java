package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.batch.PaymentStagePartitioner;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.batch.contract.CapCheckBatchPort;
import com.susukkang.fgc.validation.batch.contract.ContractSkip;
import com.susukkang.fgc.validation.batch.contract.SkipLimitExceededException;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class CapCheckTaskletTest {

    @Mock CapCheckBatchPort capCheckBatchPort;

    @Test
    void delegatesJobAndPartitionContextAndRecordsSuccessfulWrites() {
        TestContext testContext = context(118L, PaymentStage.GA_TO_FC);
        given(capCheckBatchPort.check(
                org.mockito.ArgumentMatchers.any(ValidationStepContext.class),
                org.mockito.ArgumentMatchers.eq(PaymentStage.GA_TO_FC)))
                .willReturn(StepProcessingResult.success(3));

        RepeatStatus status = new CapCheckTasklet(capCheckBatchPort)
                .execute(testContext.contribution(), testContext.chunkContext());

        ArgumentCaptor<ValidationStepContext> contextCaptor =
                ArgumentCaptor.forClass(ValidationStepContext.class);
        verify(capCheckBatchPort).check(contextCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(PaymentStage.GA_TO_FC));
        assertThat(contextCaptor.getValue().validationRunId()).isEqualTo(118L);
        assertThat(contextCaptor.getValue().job().validationMonth())
                .isEqualTo(java.time.LocalDate.of(2026, 8, 1));
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(testContext.contribution().getWriteCount()).isEqualTo(3);
        assertThat(testContext.contribution().getProcessSkipCount()).isZero();
    }

    @Test
    void finishesWhenSkippedCountEqualsLimit() {
        TestContext testContext = context(118L, PaymentStage.INSURER_TO_GA);
        given(capCheckBatchPort.check(
                org.mockito.ArgumentMatchers.any(ValidationStepContext.class),
                org.mockito.ArgumentMatchers.eq(PaymentStage.INSURER_TO_GA)))
                .willReturn(resultWithSkips(3, 100));

        RepeatStatus status = new CapCheckTasklet(capCheckBatchPort)
                .execute(testContext.contribution(), testContext.chunkContext());

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(testContext.contribution().getWriteCount()).isEqualTo(3);
        assertThat(testContext.contribution().getProcessSkipCount()).isEqualTo(100);
    }

    @Test
    void failsWhenSkippedCountExceedsLimit() {
        TestContext testContext = context(118L, PaymentStage.GA_TO_FC);
        given(capCheckBatchPort.check(
                org.mockito.ArgumentMatchers.any(ValidationStepContext.class),
                org.mockito.ArgumentMatchers.eq(PaymentStage.GA_TO_FC)))
                .willReturn(resultWithSkips(3, 101));

        assertThatThrownBy(() -> new CapCheckTasklet(capCheckBatchPort)
                .execute(testContext.contribution(), testContext.chunkContext()))
                .isInstanceOf(SkipLimitExceededException.class)
                .hasMessageContaining("capCheckStep")
                .hasMessageContaining("GA_TO_FC");
        assertThat(testContext.contribution().getWriteCount()).isEqualTo(3);
        assertThat(testContext.contribution().getProcessSkipCount()).isEqualTo(101);
    }

    private StepProcessingResult resultWithSkips(long processedCount, int skippedCount) {
        List<ContractSkip> skips = LongStream.rangeClosed(1, skippedCount)
                .mapToObj(contractId -> new ContractSkip(
                        contractId, "CAP_CHECK_FAILED", "test skip"))
                .toList();
        return new StepProcessingResult(processedCount, skippedCount, 0, skips);
    }

    private TestContext context(Long validationRunId, PaymentStage paymentStage) {
        JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, "MonthlyValidationJob"),
                new JobParametersBuilder()
                        .addString("validationMonth", "2026-08")
                        .addLong("runNo", 1L)
                        .addString("runType", "MONTHLY")
                        .addLong("triggeredBy", 1L)
                        .addString("requestId", "req-cap-tasklet")
                        .toJobParameters());
        StepExecution stepExecution = new StepExecution("capCheckWorkerStep", jobExecution);
        stepExecution.getExecutionContext().putString(
                PaymentStagePartitioner.PAYMENT_STAGE_KEY,
                paymentStage.name());
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));
        ValidationRunBatchContext.putValidationRunId(chunkContext, validationRunId);
        return new TestContext(chunkContext, new StepContribution(stepExecution));
    }

    private record TestContext(ChunkContext chunkContext, StepContribution contribution) {
    }
}
