package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.cap.dto.CapCalculationCommand;
import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : CapCheckTasklet
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-13
 */
@RequiredArgsConstructor
public class CapCheckTasklet implements Tasklet {
    private CapCalculator capCalculator;
    @Override
    public @Nullable RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {

        Long validationRunId = ValidationRunBatchContext.getValidationRunId(chunkContext);

        CapCalculationCommand command =
                new CapCalculationCommand(
                        contractId,
                        paymentStage,
                        asOfDate,
                        checkKind,
                        validationRunId,
                        complianceEvidenceAmount
                );
        JobExecution jobExecution = chunkContext.getStepContext().getStepExecution().getJobExecution();
        CapCalculationResult result = capCalculator.calculate(command);
        capService.checkCap(jobExecution.getJobParameters(contract));
        return RepeatStatus.FINISHED;
    }
}