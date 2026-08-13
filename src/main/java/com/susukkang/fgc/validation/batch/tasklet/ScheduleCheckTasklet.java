package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.service.ValidationRunScheduleService;
import lombok.RequiredArgsConstructor;
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
        Long validationRunId =
                ValidationRunBatchContext.getValidationRunId(chunkContext);

        var result = validationRunScheduleService.validateContractSchedules(validationRunId);
        contribution.incrementWriteCount(result.processedCount() - result.skippedCount());

        return RepeatStatus.FINISHED;
    }
}
