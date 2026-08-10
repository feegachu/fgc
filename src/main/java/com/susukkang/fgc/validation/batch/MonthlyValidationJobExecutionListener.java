package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import com.susukkang.fgc.validation.service.ValidationRunBatchAuditService;
import com.susukkang.fgc.validation.service.ValidationRunBatchProgressService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobExecutionListener;

/**
 * Job 전체 종료 시점의 마무리 담당
 */
@Slf4j
@RequiredArgsConstructor
public class MonthlyValidationJobExecutionListener implements JobExecutionListener {

    private final ValidationRunBatchProgressService progressService;
    private final ValidationRunBatchAuditService auditService;

    @Override
    public void beforeJob(JobExecution jobExecution) {
        RequestIdContext.set(MonthlyValidationJobParameters.from(jobExecution.getJobParameters()).requestId());
    }

    @Override
    public void afterJob(JobExecution jobExecution) {
        try {
            if (jobExecution.getStatus() != BatchStatus.COMPLETED) {
                return;
            }
            Long validationRunId = ValidationRunBatchContext.getValidationRunId(jobExecution.getExecutionContext());
            if (validationRunId == null) {
                log.warn("Job이 COMPLETED인데 validationRunId가 없습니다 — createRunStep 배선을 확인하세요.");
                return;
            }
            MonthlyValidationJobParameters parameters = MonthlyValidationJobParameters.from(jobExecution.getJobParameters());
            progressService.completeRun(validationRunId);
            auditService.recordCompleted(validationRunId, parameters);
        } finally {
            RequestIdContext.clear();
        }
    }
}
