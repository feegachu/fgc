package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.batch.contract.ReconciliationBatchPort;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * 설명 : 월 통합검증 Step 7에서 두 지급단계를 순서대로 대사한다.
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@RequiredArgsConstructor
public class ReconciliationTasklet implements Tasklet {

    private final ReconciliationBatchPort reconciliationBatchPort;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long validationRunId = ValidationRunBatchContext.getValidationRunId(chunkContext);
        if (validationRunId == null) {
            throw new IllegalStateException("FGC-FUN-041 validationRunId가 실행 컨텍스트에 없습니다.");
        }
        MonthlyValidationJobParameters parameters = MonthlyValidationJobParameters.from(
                chunkContext.getStepContext().getStepExecution().getJobParameters());
        ValidationJobContext jobContext = new ValidationJobContext(
                parameters.validationMonth(),
                parameters.runNo(),
                parameters.runType(),
                parameters.triggeredBy(),
                parameters.requestId()
        );
        ValidationStepContext stepContext = new ValidationStepContext(validationRunId, jobContext);
        for (PaymentStage paymentStage : PaymentStage.values()) {
            reconciliationBatchPort.reconcile(stepContext, paymentStage);
        }
        return RepeatStatus.FINISHED;
    }
}
