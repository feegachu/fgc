package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import com.susukkang.fgc.validation.service.ValidationRunBatchAuditService;
import com.susukkang.fgc.validation.service.ValidationRunBatchLifecycleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;

/**
 * 8개 Step 각각에 붙는 공통 진행상황 리스너
 * Step 하나당 인스턴스 하나를 만들어 stepNo(1~8)를 생성자로 받음
 */
@Slf4j
public class ValidationRunStepProgressListener implements StepExecutionListener {

    private final int stepNo;
    private final boolean initialStep;
    private final ValidationRunBatchLifecycleService lifecycleService;
    private final ValidationRunBatchAuditService auditService;

    public ValidationRunStepProgressListener(int stepNo, boolean initialStep,
                                              ValidationRunBatchLifecycleService lifecycleService,
                                              ValidationRunBatchAuditService auditService) {
        this.stepNo = stepNo;
        this.initialStep = initialStep;
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
    }

    /**
     * afterStep은 Spring Batch가 "이 Step이 성공했든 실패했든, 끝난 직후" 정확히 한 번 불러주는 콜백
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        // 1) 이 Step이 속한 Job 전체의 ExecutionContext에서 validationRunId를 꺼냄
        Long validationRunId = ValidationRunBatchContext.getValidationRunId(
                stepExecution.getJobExecution().getExecutionContext());

        if (validationRunId == null) {
            // createRunStep 자체가 행을 만들기 전에 실패한 경우
            log.warn("validationRunId가 아직 없어 Step {} 진행상황을 기록하지 못했습니다 (stepExecution={})",
                    stepNo, stepExecution.getStepName());
            if (initialStep && stepExecution.getStatus() != BatchStatus.COMPLETED) {
                MonthlyValidationJobParameters parameters = MonthlyValidationJobParameters.from(
                        stepExecution.getJobExecution().getJobParameters());
                auditService.recordRunCreationFailed(stepExecution.getJobExecution().getId(), parameters,
                        summarizeFailure(stepExecution));
            }
            return stepExecution.getExitStatus();
        }

        MonthlyValidationJobParameters parameters = MonthlyValidationJobParameters.from(
                stepExecution.getJobExecution().getJobParameters());

        // 2) stepExecution.getStatus()는 BatchStatus(COMPLETED/FAILED/...) 성공여부 판정
        if (stepExecution.getStatus() == BatchStatus.COMPLETED) {
            if (initialStep) {
                // 1 createRunStep 성공 = validation_run이 막 CREATED 상태로 만들어졌다는 뜻
                lifecycleService.start(validationRunId, parameters);
            } else {
                // 2~8 성공 = RUNNING 상태를 유지한 채 current_step만 이 Step의 번호로 전진
                lifecycleService.advance(validationRunId, stepNo);
            }
        } else {
            // 실패 사유는 이 Step에서 던진 예외들의 메시지를 모아 남김
            String failureMessage = summarizeFailure(stepExecution);
            lifecycleService.fail(validationRunId, stepNo, parameters, failureMessage);
        }
        return stepExecution.getExitStatus();
    }

    private String summarizeFailure(StepExecution stepExecution) {
        String joined = stepExecution.getFailureExceptions().stream()
                .map(Throwable::getMessage)
                .filter(msg -> msg != null && !msg.isBlank())
                .reduce((a, b) -> a + " | " + b)
                .orElse("Step " + stepExecution.getStepName() + " 실패 (상세 메시지 없음)");
        return joined.length() > 2000 ? joined.substring(0, 2000) : joined;
    }
}
