package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.service.ValidationTargetSelectionService;
import org.junit.jupiter.api.BeforeEach;
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

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SelectTargetTaskletTest {

    @Mock
    private ValidationTargetSelectionService selectionService;

    private SelectTargetTasklet tasklet;

    @BeforeEach
    void setUp() {
        tasklet = new SelectTargetTasklet(selectionService);
    }

    @Test
    void delegatesValidationRunIdAndMonthToSelectionService() {
        ChunkContext chunkContext = newChunkContext();
        ValidationRunBatchContext.putValidationRunId(chunkContext, 118L);

        RepeatStatus result = tasklet.execute(null, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(selectionService).selectTargets(118L, LocalDate.of(2026, 8, 1));
    }

    private ChunkContext newChunkContext() {
        JobParameters parameters = new JobParametersBuilder()
                .addString("validationMonth", "2026-08")
                .addLong("runNo", 1L)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 1L)
                .addString("requestId", "req-select-target-1")
                .toJobParameters();
        JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, "MonthlyValidationJob"), parameters);
        StepExecution stepExecution = new StepExecution("selectTargetStep", jobExecution);
        return new ChunkContext(new StepContext(stepExecution));
    }
}
