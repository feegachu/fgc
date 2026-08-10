package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * ⑦양방향 대사(reconciliationStep, IF-BAT-01 표의 "지급단계 2회"). capCheckStep(④)처럼
 * 별도 Partitioner로 나누지 않고, 이 Step 하나가 두 지급단계를 순서대로 처리하는 방식을
 * 택했다 — 대사는 원장(⑥)이 이미 지급단계 구분 없이 하나로 기표된 뒤에 도는 단계라 두
 * 지급단계 결과를 한 트랜잭션 흐름에서 순서대로 비교하는 편이 더 자연스럽다(파티션으로
 * 나누면 두 워커가 같은 reconciliation_run을 동시에 건드릴 위험만 생긴다). 실제 대사 로직을
 * 붙이는 이슈에서 이 방식이 안 맞으면 PaymentStagePartitioner로 바꿔도 된다 — Step
 * 배선(리스너·current_step 갱신)에는 영향 없다.
 */
@Slf4j
public class ReconciliationPlaceholderTasklet implements Tasklet {

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        Long validationRunId = ValidationRunBatchContext.getValidationRunId(chunkContext);
        for (PaymentStage stage : PaymentStage.values()) {
            log.info("[TODO][validationRunId={}] ⑦양방향 대사 (paymentStage={}) — 실제 로직 미구현",
                    validationRunId, stage);
        }
        return RepeatStatus.FINISHED;
    }
}
