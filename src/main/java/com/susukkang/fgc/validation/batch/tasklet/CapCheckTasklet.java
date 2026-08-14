package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.batch.PaymentStagePartitioner;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.batch.contract.CapCheckBatchPort;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;
/**
 * 설명 : CapCheckTasklet
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-13
 */
@RequiredArgsConstructor
public class CapCheckTasklet implements Tasklet {

    private final CapCheckBatchPort capCheckBatchPort;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext ) {
        Long validationRunId =
                ValidationRunBatchContext.getValidationRunId(chunkContext);

        MonthlyValidationJobParameters parameters =
                MonthlyValidationJobParameters.from(
                        chunkContext.getStepContext()
                                .getStepExecution()
                                .getJobParameters()
                );

        String paymentStageValue =
                chunkContext.getStepContext()
                        .getStepExecution()
                        .getExecutionContext()
                        .getString(PaymentStagePartitioner.PAYMENT_STAGE_KEY);

        PaymentStage paymentStage =
                PaymentStage.valueOf(paymentStageValue);

        ValidationJobContext jobContext = new ValidationJobContext(
                parameters.validationMonth(),
                parameters.runNo(),
                parameters.runType(),
                parameters.triggeredBy(),
                parameters.requestId()
        );

        StepProcessingResult result = capCheckBatchPort.check(
                new ValidationStepContext(validationRunId, jobContext),
                paymentStage
        );

        contribution.incrementWriteCount(result.processedCount());

        return RepeatStatus.FINISHED;
    }
}