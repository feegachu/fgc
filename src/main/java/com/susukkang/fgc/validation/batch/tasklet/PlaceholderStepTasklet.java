package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.PaymentStagePartitioner;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * #57은 MonthlyValidationJob의 8단계 골격과 실행 순서만 잡는다. ②대상선별·③스케줄재생성·
 * ⑤차익거래검증·⑥원장기표/균형검사·⑧예외생성의 실제 판정 로직은 각각 별도 이슈(대상선별,
 * 차익거래, 원장기표, 예외생성)에서 채운다. 그 이슈들이 이 클래스를 실제 Tasklet 구현으로
 * 교체하면 된다 — Step 배선(순서·리스너·current_step 갱신)은 이미 이 골격이 다 하고 있으므로
 * 그대로 두고 execute() 본문만 바뀐다.
 *
 * capCheckStep의 파티션 워커도 이 클래스를 재사용한다: PaymentStagePartitioner가 심어 둔
 * paymentStage를 Step의 ExecutionContext에서 읽어 로그에 남긴다 — capCheckStep을 담당할
 * 이슈에서 이 자리에 CapCalculator 호출을 넣을 때 어떤 지급단계를 처리 중인지 이미 알 수 있게
 * 하기 위해서다(REG-10: INSURER_TO_GA/GA_TO_FC 게이지를 절대 합치지 않는다).
 */
@Slf4j
public class PlaceholderStepTasklet implements Tasklet {

    private final String stepDescription;

    public PlaceholderStepTasklet(String stepDescription) {
        this.stepDescription = stepDescription;
    }

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // validationRunId는 이 Step이 어떤 실행에 속하는지 알려준다 — 실제 구현이 들어가면
        // 이 값으로 validation_target 등 관련 테이블을 조회/저장하게 된다.
        Long validationRunId = ValidationRunBatchContext.getValidationRunId(chunkContext);

        // getStepExecutionContext()는 "이 Step(파티션이면 이 파티션) 전용" 컨텍스트다 —
        // Job 전체 컨텍스트(ValidationRunBatchContext가 쓰는 것)와는 다른 대상이다.
        // capCheckWorkerStep이 아닌 일반 Step으로 이 Tasklet이 쓰이면 이 키가 아예 없어서
        // paymentStage는 null이 된다 — 그래서 null 여부로 "내가 파티션 워커로 불렸는지"를
        // 구분할 수 있다.
        Object paymentStage = chunkContext.getStepContext().getStepExecutionContext()
                .get(PaymentStagePartitioner.PAYMENT_STAGE_KEY);

        // 지금은 로그만 남기고 끝 — 실제 이슈에서 이 if/else 블록 전체를 진짜 로직으로
        // 바꾸면 된다. paymentStage가 있는 경우(capCheckStep)는 그 값에 따라 INSURER_TO_GA용
        // 계산과 GA_TO_FC용 계산을 분기해야 한다는 걸 로그로도 보여준다.
        if (paymentStage != null) {
            log.info("[TODO][validationRunId={}] {} (paymentStage={}) — 실제 로직 미구현",
                    validationRunId, stepDescription, paymentStage);
        } else {
            log.info("[TODO][validationRunId={}] {} — 실제 로직 미구현", validationRunId, stepDescription);
        }

        return RepeatStatus.FINISHED;
    }
}
