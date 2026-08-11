package com.susukkang.fgc.validation.batch.daily;

import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.item.ExecutionContext;

import java.time.OffsetDateTime;

/**
 * DailyChangedContractJob 전용 Job-ExecutionContext 헬퍼
 */
public final class DailyBatchContext {

    private static final String LAST_PROCESSED_AT_KEY = "lastProcessedAt";
    private static final String RUN_STARTED_AT_KEY = "runStartedAt";

    private DailyBatchContext() {
    }

    // CreateDailyRunTasklet이 Reader가 이번 실행에서 기준으로 삼을 watermark 시작점을 저장
    public static void putLastProcessedAt(ChunkContext chunkContext, OffsetDateTime value) {
        jobExecutionContext(chunkContext).putString(LAST_PROCESSED_AT_KEY, value.toString());
    }

    public static OffsetDateTime getLastProcessedAt(ExecutionContext jobExecutionContext) {
        String raw = jobExecutionContext.getString(LAST_PROCESSED_AT_KEY, null);
        return raw == null ? null : OffsetDateTime.parse(raw);
    }

    // CreateDailyRunTasklet이 "이번 배치 실행이 시작된 시각"을 못박아 둔다 — changedContractStep이
    // 끝난 뒤 watermark를 이 값으로 전진시켜야, Step 실행 도중에 새로 들어온 변경 이벤트를
    // "이미 처리했다"고 잘못 표시하지 않는다(그 이벤트는 다음 날 배치가 다시 잡아낸다).
    public static void putRunStartedAt(ChunkContext chunkContext, OffsetDateTime value) {
        jobExecutionContext(chunkContext).putString(RUN_STARTED_AT_KEY, value.toString());
    }

    public static OffsetDateTime getRunStartedAt(ExecutionContext jobExecutionContext) {
        String raw = jobExecutionContext.getString(RUN_STARTED_AT_KEY, null);
        return raw == null ? null : OffsetDateTime.parse(raw);
    }

    private static ExecutionContext jobExecutionContext(ChunkContext chunkContext) {
        return chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext();
    }
}
