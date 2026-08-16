package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.batch.contract.ExceptionGenerationPort;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ExceptionGenerationTaskletTest {

    @Mock
    private ExceptionGenerationPort exceptionGenerationPort;

    @Test
    void delegatesValidationContextAndRecordsCreatedCount() {
        TestContext testContext = context(118L);
        given(exceptionGenerationPort.generate(any(ValidationStepContext.class)))
                .willReturn(StepProcessingResult.success(7));

        RepeatStatus status = new ExceptionGenerationTasklet(exceptionGenerationPort)
                .execute(testContext.contribution(), testContext.chunkContext());

        ArgumentCaptor<ValidationStepContext> captor =
                ArgumentCaptor.forClass(ValidationStepContext.class);
        verify(exceptionGenerationPort).generate(captor.capture());

        ValidationStepContext actual = captor.getValue();
        assertThat(actual.validationRunId()).isEqualTo(118L);
        assertThat(actual.job().validationMonth()).isEqualTo(java.time.LocalDate.of(2026, 8, 1));
        assertThat(actual.job().runNo()).isEqualTo(1L);
        assertThat(actual.job().requestId()).isEqualTo("req-exception-tasklet");
        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(testContext.contribution().getWriteCount()).isEqualTo(7L);
    }

    private TestContext context(Long validationRunId) {
        JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, "MonthlyValidationJob"),
                new JobParametersBuilder()
                        .addString("validationMonth", "2026-08")
                        .addLong("runNo", 1L)
                        .addString("runType", "MONTHLY")
                        .addLong("triggeredBy", 1L)
                        .addString("requestId", "req-exception-tasklet")
                        .toJobParameters());
        StepExecution stepExecution = new StepExecution("exceptionGenerationStep", jobExecution);
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));
        ValidationRunBatchContext.putValidationRunId(chunkContext, validationRunId);
        return new TestContext(chunkContext, new StepContribution(stepExecution));
    }

    private record TestContext(ChunkContext chunkContext, StepContribution contribution) {
    }
}
