package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.batch.contract.ExceptionGenerationPort;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
import org.springframework.lang.NonNull;

/**
 * 설명 : ExceptionGenerationTasklet
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-14
 */
@RequiredArgsConstructor
public class ExceptionGenerationTasklet implements Tasklet {
    private final ExceptionGenerationPort exceptionGenerationPort;
    @Override
    public RepeatStatus execute(@NonNull StepContribution contribution, @NonNull ChunkContext chunkContext) {
        Long validationRunId =
                ValidationRunBatchContext.getValidationRunId(chunkContext);

        MonthlyValidationJobParameters parameters =
                MonthlyValidationJobParameters.from(
                        chunkContext.getStepContext()
                                .getStepExecution()
                                .getJobParameters()
                );

        ValidationJobContext jobContext = new ValidationJobContext(
                parameters.validationMonth(),
                parameters.runNo(),
                parameters.runType(),
                parameters.triggeredBy(),
                parameters.requestId()
        );

        ValidationStepContext stepContext =
                new ValidationStepContext(validationRunId, jobContext);

        StepProcessingResult result =
                exceptionGenerationPort.generate(stepContext);

        contribution.incrementWriteCount(result.processedCount());

        return RepeatStatus.FINISHED;
    }
}
