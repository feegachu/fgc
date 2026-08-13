package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.batch.contract.ContractSkip;
import com.susukkang.fgc.validation.batch.contract.SkipLimitExceededException;
import com.susukkang.fgc.validation.batch.contract.ScheduleRegenerationPort;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ScheduleCheckTaskletTest {

    @Mock ScheduleRegenerationPort scheduleRegenerationPort;

    @Test
    void delegatesValidationRunIdAndFinishes() {
        TestContext testContext = context(118L);
        given(scheduleRegenerationPort.regenerateSchedules(any(ValidationStepContext.class)))
                .willReturn(StepProcessingResult.success(3));

        RepeatStatus status = new ScheduleCheckTasklet(scheduleRegenerationPort)
                .execute(testContext.contribution(), testContext.chunkContext());

        assertThat(status).isEqualTo(RepeatStatus.FINISHED);
        assertThat(testContext.contribution().getWriteCount()).isEqualTo(3);
        verify(scheduleRegenerationPort).regenerateSchedules(any(ValidationStepContext.class));
    }

    @Test
    void failsWhenSkipLimitIsExceeded() {
        TestContext testContext = context(118L);
        List<ContractSkip> skips = java.util.stream.LongStream.rangeClosed(1, 101)
                .mapToObj(id -> new ContractSkip(id, "DATA_QUALITY", "오류"))
                .toList();
        given(scheduleRegenerationPort.regenerateSchedules(any(ValidationStepContext.class)))
                .willReturn(new StepProcessingResult(101, 101, 0, skips));

        assertThatThrownBy(() -> new ScheduleCheckTasklet(scheduleRegenerationPort)
                .execute(testContext.contribution(), testContext.chunkContext()))
                .isInstanceOf(SkipLimitExceededException.class);
    }

    private TestContext context(Long validationRunId) {
        JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, "MonthlyValidationJob"),
                new org.springframework.batch.core.JobParametersBuilder()
                        .addString("validationMonth", "2026-08")
                        .addLong("runNo", 1L)
                        .addString("runType", "MONTHLY")
                        .addLong("triggeredBy", 1L)
                        .addString("requestId", "req-schedule-check")
                        .toJobParameters());
        StepExecution stepExecution = new StepExecution("regenerateScheduleStep", jobExecution);
        ChunkContext chunkContext = new ChunkContext(new StepContext(stepExecution));
        ValidationRunBatchContext.putValidationRunId(chunkContext, validationRunId);
        return new TestContext(chunkContext, new StepContribution(stepExecution));
    }

    private record TestContext(ChunkContext chunkContext, StepContribution contribution) {
    }
}
