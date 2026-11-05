package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.validation.batch.contract.LedgerImbalanceDetectedException;
import com.susukkang.fgc.validation.batch.contract.SkipLimitExceededException;
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
    private final FgcMessageResolver messageResolver;

    public ValidationRunStepProgressListener(int stepNo, boolean initialStep,
                                              ValidationRunBatchLifecycleService lifecycleService,
                                              ValidationRunBatchAuditService auditService,
                                              FgcMessageResolver messageResolver) {
        this.stepNo = stepNo;
        this.initialStep = initialStep;
        this.lifecycleService = lifecycleService;
        this.auditService = auditService;
        this.messageResolver = messageResolver;
    }

    /**
     * afterStep은 Spring Batch가 "이 Step이 성공했든 실패했든, 끝난 직후" 정확히 한 번 불러주는 콜백.
     */
    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        try {
            return doAfterStep(stepExecution);
        } catch (RuntimeException e) {
            // ExitStatus만 FAILED로 돌려주면 SimpleJob(.start().next() 체인)은 BatchStatus가
            // COMPLETED인 한 다음 Step으로 넘어간다. Step의 BatchStatus까지 FAILED로 올려야
            // SimpleJob이 여기서 멈추고 Job도 FAILED가 된다.
            log.error("Step {}({}) 진행상황 기록 중 예외가 발생해 Step·Job을 FAILED로 전파합니다",
                    stepNo, stepExecution.getStepName(), e);
            stepExecution.upgradeStatus(BatchStatus.FAILED);
            stepExecution.addFailureException(e);
            return ExitStatus.FAILED;
        }
    }

    private ExitStatus doAfterStep(StepExecution stepExecution) {
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
                        summarizeFailure(stepExecution, parameters.requestId()));
            }
            return stepExecution.getExitStatus();
        }

        MonthlyValidationJobParameters parameters = MonthlyValidationJobParameters.from(
                stepExecution.getJobExecution().getJobParameters());

        // 2) stepExecution.getStatus()는 BatchStatus(COMPLETED/FAILED/...) 성공여부 판정
        if (stepExecution.getStatus() == BatchStatus.COMPLETED) {
            if (initialStep && stepExecution.getJobParameters().getLong("restartValidationRunId") == null) {
                // 1 createRunStep 성공 = validation_run이 막 CREATED 상태로 만들어졌다는 뜻
                lifecycleService.start(validationRunId, parameters);
            } else if (!initialStep) {
                // 2~8 성공 = RUNNING 상태를 유지한 채 current_step만 이 Step의 번호로 전진
                lifecycleService.advance(validationRunId, stepNo, parameters);
            }
        } else {
            String failureMessage = summarizeFailure(stepExecution, parameters.requestId());
            lifecycleService.fail(validationRunId, stepNo, parameters, failureMessage);
        }
        return stepExecution.getExitStatus();
    }

    /**
     * 실패 사유는 화면과 감사로그에 그대로 보이므로 사용자 문장만 남긴다(화면정의서 공통규칙 10, SRC-074).
     * 예외 원문은 요청번호와 함께 서버 로그에 남겨 원인 추적을 잃지 않는다.
     */
    private String summarizeFailure(StepExecution stepExecution, String requestId) {
        stepExecution.getFailureExceptions().forEach(failure ->
                log.error("Step {}({}) 실패 requestId={}", stepNo, stepExecution.getStepName(), requestId, failure));
        String joined = stepExecution.getFailureExceptions().stream()
                .map(failure -> userMessage(failure, requestId))
                .distinct()
                .reduce((a, b) -> a + " | " + b)
                .orElse(stepNo + "단계에서 실패했습니다.");
        return joined.length() > 2000 ? joined.substring(0, 2000) : joined;
    }

    private String userMessage(Throwable failure, String requestId) {
        if (failure instanceof LedgerImbalanceDetectedException) {
            return failure.getMessage() + " 검증원장에서 확인한 뒤 실패 지점부터 재시작하세요.";
        }
        if (failure instanceof SkipLimitExceededException skip) {
            return "처리하지 못한 계약 " + skip.getSkippedCount() + "건이 허용 한도 " + skip.getSkipLimit()
                    + "건을 넘었습니다. 계약 자료를 확인한 뒤 실패 지점부터 재시작하세요.";
        }
        if (failure instanceof FgcBusinessException business && !business.getErrorCode().getStatus().is5xxServerError()) {
            return messageResolver.resolve(business.getErrorCode(), business.getParams());
        }
        return stepNo + "단계 처리 중 오류가 발생했습니다. 요청번호 " + requestId + "를 담당자에게 알려주세요.";
    }
}
