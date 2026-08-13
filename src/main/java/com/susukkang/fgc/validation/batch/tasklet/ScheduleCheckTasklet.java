package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.batch.contract.SkipLimitExceededException;
import com.susukkang.fgc.validation.batch.contract.ScheduleRegenerationPort;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
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
    private static final long DEFAULT_SKIP_LIMIT = 100L;

    private final ScheduleRegenerationPort scheduleRegenerationPort;
    @Override
    public RepeatStatus execute(
            StepContribution contribution,
            ChunkContext chunkContext) {
        Long validationRunId =
                ValidationRunBatchContext.getValidationRunId(chunkContext);

        MonthlyValidationJobParameters parameters = MonthlyValidationJobParameters.from(
                chunkContext.getStepContext().getStepExecution().getJobParameters());

        ValidationJobContext jobContext = new ValidationJobContext(
                parameters.validationMonth(),
                parameters.runNo(),
                parameters.runType(),
                parameters.triggeredBy(),
                parameters.requestId());

        var result = scheduleRegenerationPort.regenerateSchedules(
                new ValidationStepContext(validationRunId, jobContext));
        if (result.skippedCount() > DEFAULT_SKIP_LIMIT) {
            throw new SkipLimitExceededException(
                    "regenerateScheduleStep", result.skippedCount(), DEFAULT_SKIP_LIMIT);
        }
        contribution.incrementWriteCount(result.processedCount() - result.skippedCount());

        return RepeatStatus.FINISHED;
    }
}
