package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import com.susukkang.fgc.validation.service.ValidationRunScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * 설명 : ScheduleCheckTasklet
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-13
 */
@RequiredArgsConstructor
public class ScheduleCheckTasklet implements Tasklet {
    private final ValidationRunScheduleService validationRunScheduleService;
    @Override
    public RepeatStatus execute(
            StepContribution contribution,
            ChunkContext chunkContext) {
        JobParameters jobParameters = chunkContext.getStepContext()
                .getStepExecution()
                .getJobParameters();

        MonthlyValidationJobParameters params =
                MonthlyValidationJobParameters.from(jobParameters);

        Long validationRunId =
                ValidationRunBatchContext.getValidationRunId(chunkContext);

        validationRunScheduleService.validateContractSchedules(validationRunId);

        return RepeatStatus.FINISHED;
    }
}