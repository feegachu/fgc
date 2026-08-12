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
 *      AuditLogMapper 등이 남기는 감사로그의 request_id가 이 배치 실행과 같은 값을 갖게함
 *      afterJob의 finally에서 반드시 지움
 *
 *   2) afterJob: 9개 Step이 전부 BatchStatus.COMPLETED로 끝났으면 validation_run을
 *      COMPLETED로 전이
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
            // FAILED 전이까지 이미 끝냈다는 뜻
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
