package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.PaymentStagePartitioner;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

@Slf4j
public class PlaceholderStepTasklet implements Tasklet {

    private final String stepDescription;

    public PlaceholderStepTasklet(String stepDescription) {
        this.stepDescription = stepDescription;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // validationRunId는 이 Step이 어떤 실행에 속하는지 알려줌
        Long validationRunId = ValidationRunBatchContext.getValidationRunId(chunkContext);

        // getStepExecutionContext(); 이 Step(파티션이면 이 파티션) 전용 컨텍스트
        Object paymentStage = chunkContext.getStepContext().getStepExecutionContext()
                .get(PaymentStagePartitioner.PAYMENT_STAGE_KEY);

        // 일단 로그만 남기고 끝 — 실제 이슈에서 이 if/else 블록 전체를 진짜 로직으로 바꾸기
        if (paymentStage != null) {
            log.info("[TODO][validationRunId={}] {} (paymentStage={}) — 실제 로직 미구현",
                    validationRunId, stepDescription, paymentStage);
        } else {
            log.info("[TODO][validationRunId={}] {} — 실제 로직 미구현", validationRunId, stepDescription);
        }

        throw new PlaceholderStepExecutionBlockedException(stepDescription);
    }
}
