package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationRunCompletionGate;
import com.susukkang.fgc.validation.batch.contract.ValidationRunNotCompletableException;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import com.susukkang.fgc.validation.service.ValidationRunBatchLifecycleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * Job 전체 종료 시점의 마무리를 담당한다. 각 Step의 성공·실패는
 * ValidationRunStepProgressListener가 이미 다 기록했으므로(current_step 갱신, 실패 시 FAILED
 * 전이), 여기서 하는 일은 두 가지뿐이다.
 *
 *   1) beforeJob: RequestIdContext에 이번 실행의 requestId를 심어서, Step 안에서
 *      AuditLogMapper 등이 남기는 감사로그의 request_id가 이 배치 실행과 같은 값을 갖게 한다
 *      (2-5절 헤더 X-Request-Id와 같은 관례). afterJob의 finally에서 반드시 지운다 —
 *      스레드가 재사용되는 실행 환경(예: TaskExecutorPartitionHandler가 스레드풀을 쓰는 경우)에서
 *      이 값이 다음 실행으로 새어나가지 않게 하기 위해서다.
 *
 *   2) afterJob: 9개 Step이 전부 BatchStatus.COMPLETED로 끝났을 때만, #75가 요구하는
 *      "완료 조건"을 ValidationRunCompletionGate에게 마지막으로 한 번 더 확인받은 뒤에만
 *      validation_run을 COMPLETED로 전이한다. Step들의 개별 성공은 "그 Step이 맡은 일은
 *      제대로 됐다"는 뜻일 뿐, "이번 실행 전체 결과가 완결됐다"는 것과 같은 말이 아니다 —
 *      예를 들어 exceptionGenerationStep(⑧)은 예외를 "정상적으로 생성"만 해도 성공(COMPLETED)
 *      이지만, 그렇게 생성된 예외 중 치명(CRITICAL) 등급이 있다면 이번 실행은 아직 완결된
 *      게 아니다. 이 간극을 메우는 게 ValidationRunCompletionGate의 역할이다.
 */
@Slf4j
@RequiredArgsConstructor
public class MonthlyValidationJobExecutionListener implements JobExecutionListener {

    private final ValidationRunBatchLifecycleService lifecycleService;
    private final ValidationRunCompletionGate completionGate;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        RequestIdContext.set(MonthlyValidationJobParameters.from(jobExecution.getJobParameters()).requestId());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        try {
            // 1) jobExecution.getStatus()는 Job 전체의 최종 상태다 — 9개 Step이 전부
            //    COMPLETED여야만 Job도 COMPLETED가 된다(하나라도 FAILED면 Job 전체가
            //    FAILED). 그러니 이 if를 통과 못 했다는 건 이미 어떤 Step이 실패했고,
            //    그 Step의 ValidationRunStepProgressListener가 FAILED 전이까지 이미
            //    끝냈다는 뜻 — 여기서 추가로 할 일이 없다.
            if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
                return;
            }

            // 2) createRunStep이 Job의 ExecutionContext에 심어 둔 값을 꺼낸다. COMPLETED인데
            //    이 값이 없는 건 배선 오류(정상 흐름에서는 절대 일어나지 않아야 함)이므로
            //    조용히 넘기지 않고 경고를 남긴다.
            Long validationRunId = ValidationRunBatchContext.getValidationRunId(jobExecution.getExecutionContext());
            if (validationRunId == null) {
                log.warn("Job이 COMPLETED인데 validationRunId가 없습니다 — createRunStep 배선을 확인하세요.");
                return;
            }

            MonthlyValidationJobParameters parameters = MonthlyValidationJobParameters.from(jobExecution.getJobParameters());
            ValidationStepContext stepContext =
                    new ValidationStepContext(validationRunId, ValidationJobContext.from(parameters));

            // 3) 완료 조건 게이트. 지금은 NoOpValidationRunCompletionGate가 항상 통과시키지만,
            //    원장 대사·예외 생성 도메인이 실제로 만들어지면 이 자리에서 진짜로 막힐 수 있다.
            //    막히면(ValidationRunNotCompletableException) COMPLETED로 넘어가지 않고,
            //    "배치 Step은 다 성공했지만 완료 조건을 충족하지 못했다"는 사실 그대로
            //    FAILED로 전이한다 — 이미 있는 lifecycleService.fail() 경로를 그대로 재사용해서
            //    상태 전이·감사로그 원자성 보장을 새로 만들 필요가 없게 했다. stepNo는 8을
            //    쓴다 — 여기 도달했다는 건 current_step이 이미 8(예외생성까지 완료)이라서,
            //    "8단계 이후 완료 판정에서 막혔다"는 의미로 자연스럽게 이어진다.
            try {
                completionGate.verifyCompletable(stepContext);
            } catch (ValidationRunNotCompletableException e) {
                log.warn("validationRunId={} 완료 조건 미충족으로 FAILED 처리합니다: {}", validationRunId, e.getMessage());
                lifecycleService.fail(validationRunId, 8, parameters, "완료 조건 미충족: " + e.getMessage());
                return;
            }

            // 4) 완료 조건까지 통과했을 때만 진짜로 COMPLETED로 전이한다.
            lifecycleService.complete(validationRunId, parameters);
        } finally {
            RequestIdContext.clear();
        }
    }
}
