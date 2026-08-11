package com.susukkang.fgc.validation.batch;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.item.ExecutionContext;

public final class ValidationRunBatchContext {

    private static final String VALIDATION_RUN_ID_KEY = "validationRunId";

    private ValidationRunBatchContext() {
    }

    // CreateRunTasklet.execute()가 딱 한 번 호출
    public static void putValidationRunId(ChunkContext chunkContext, Long validationRunId) {
        jobExecutionContext(chunkContext).putLong(VALIDATION_RUN_ID_KEY, validationRunId);
    }

    // Tasklet 안에서 읽을 때 쓰는 오버로드
    public static Long getValidationRunId(ChunkContext chunkContext) {
        ExecutionContext context = jobExecutionContext(chunkContext);
        if (!context.containsKey(VALIDATION_RUN_ID_KEY)) {
            return null;
        }
        return context.getLong(VALIDATION_RUN_ID_KEY);
    }

    // Listener(ValidationRunStepProgressListener, MonthlyValidationJobExecutionListener) 쪽은
    // ChunkContext가 아니라 이미 JobExecution/StepExecution을 갖고 있어서, 그 안의
    // ExecutionContext를 곧바로 받는 이 오버로드 사용
    public static Long getValidationRunId(ExecutionContext jobExecutionContext) {
        if (!jobExecutionContext.containsKey(VALIDATION_RUN_ID_KEY)) {
            return null;
        }
        return jobExecutionContext.getLong(VALIDATION_RUN_ID_KEY);
    }

    // ChunkContext → StepContext → StepExecution → JobExecution → ExecutionContext로
    // 4단계를 타고 내려가는 게 매번 반복돼서 여기 한 곳에 모아둠
    private static ExecutionContext jobExecutionContext(ChunkContext chunkContext) {
        return chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext();
    }
}
