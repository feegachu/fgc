package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceCheckPort;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceDetectedException;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * Step 6b: IF-BAT-01 "⑥균형검사". validation_run_id 기준으로 불균형 분개 건수를 세고,
 * 1건이라도 있으면 즉시 Step을 실패시킨다(LedgerImbalanceDetectedException) —
 * MonthlyValidationStepCoordinator.checkLedgerBalance()와 같은 판단이다. Coordinator를
 * 직접 쓰지 않는 이유는 LedgerImbalanceCheckPortImpl Javadoc 참고(다른 포트 8개가 아직
 * 구현되지 않아 빈으로 등록할 수 없다).
 */
@RequiredArgsConstructor
public class LedgerImbalanceCheckTasklet implements Tasklet {

    private final LedgerImbalanceCheckPort ledgerImbalanceCheckPort;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobParameters jobParameters = chunkContext.getStepContext().getStepExecution()
                .getJobParameters();
        MonthlyValidationJobParameters params = MonthlyValidationJobParameters.from(jobParameters);

        Long validationRunId = ValidationRunBatchContext.getValidationRunId(chunkContext);

        ValidationJobContext job = new ValidationJobContext(
                params.validationMonth(), params.runNo(), params.runType(),
                params.triggeredBy(), params.requestId());
        ValidationStepContext stepContext = new ValidationStepContext(validationRunId, job);

        LedgerImbalanceResult result = ledgerImbalanceCheckPort.check(stepContext);
        if (result.imbalanceCount() > 0) {
            throw new LedgerImbalanceDetectedException(result.imbalanceCount());
        }

        return RepeatStatus.FINISHED;
    }
}
