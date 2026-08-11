package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.common.web.RequestIdContext;
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
 *   2) afterJob: 9개 Step이 전부 BatchStatus.COMPLETED로 끝났으면 validation_run을
 *      COMPLETED로 전이한다. 운영정책서(docs/FGC_가상_GA_운영정책서_v1_0.md) 실행상태표가
 *      COMPLETED를 "1~8단계 완료, 9단계 담당자 검토 대기"로만 정의하고 있어서, 여기서는
 *      추가 조건 없이 그대로 전이한다 — 원장 불균형·CRITICAL 미처리 예외·정책 누락 같은
 *      조건은 같은 문서 제44조가 "서비스가 확정(FINALIZE) 직전에 조회해 강제"하도록 정한
 *      COMPLETED→FINALIZED 조건이라, 배치가 아니라 확정 API(IF-API-51,
 *      POST /api/v1/validation-runs/{id}/finalize) 쪽 책임이다 — 이 Job에는 없다.
 */
@Slf4j
@RequiredArgsConstructor
public class MonthlyValidationJobExecutionListener implements JobExecutionListener {

    private final ValidationRunBatchLifecycleService lifecycleService;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        RequestIdContext.set(MonthlyValidationJobParameters.from(jobExecution.getJobParameters()).requestId());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        try {
            // jobExecution.getStatus()는 Job 전체의 최종 상태다 — 9개 Step이 전부 COMPLETED여야만
            // Job도 COMPLETED가 된다(하나라도 FAILED면 Job 전체가 FAILED). 그러니 이 if를 통과 못
            // 했다는 건 이미 어떤 Step이 실패했고, 그 Step의 ValidationRunStepProgressListener가
            // FAILED 전이까지 이미 끝냈다는 뜻 — 여기서 추가로 할 일이 없다.
            if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
                return;
            }

            // createRunStep이 Job의 ExecutionContext에 심어 둔 값을 꺼낸다. COMPLETED인데
            // 이 값이 없는 건 배선 오류(정상 흐름에서는 절대 일어나지 않아야 함)이므로
            // 조용히 넘기지 않고 경고를 남긴다.
            Long validationRunId = ValidationRunBatchContext.getValidationRunId(jobExecution.getExecutionContext());
            if (validationRunId == null) {
                log.warn("Job이 COMPLETED인데 validationRunId가 없습니다 — createRunStep 배선을 확인하세요.");
                return;
            }

            MonthlyValidationJobParameters parameters = MonthlyValidationJobParameters.from(jobExecution.getJobParameters());
            lifecycleService.complete(validationRunId, parameters);
        } finally {
            RequestIdContext.clear();
        }
    }
}
