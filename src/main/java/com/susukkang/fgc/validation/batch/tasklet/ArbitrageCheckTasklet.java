package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.batch.contract.ArbitrageCheckBatchPort;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * 설명 : 월 검증 Step 5에서 차익거래 검증 포트를 실행한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@RequiredArgsConstructor
public class ArbitrageCheckTasklet implements Tasklet {
    private final ArbitrageCheckBatchPort arbitrageCheckBatchPort;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long validationRunId = ValidationRunBatchContext.getValidationRunId(chunkContext);
        MonthlyValidationJobParameters parameters =
                MonthlyValidationJobParameters.
                        from(
                                chunkContext
                                        .getStepContext()
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
        arbitrageCheckBatchPort.check(new ValidationStepContext(validationRunId, jobContext));
        return RepeatStatus.FINISHED;
    }
}
