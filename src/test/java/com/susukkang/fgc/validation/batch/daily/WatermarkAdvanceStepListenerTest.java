package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.validation.mapper.BatchWatermarkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepExecution;

import java.time.OffsetDateTime;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * "온전히 성공했을 때만 watermark를 전진시킨다" 규칙을 검증한다 — Step이 FAILED로 끝나면
 * 어디까지 읽었는지 불확실하므로 절대 전진시키면 안 된다는 것이 이 리스너의 핵심 계약이다.
 */
@ExtendWith(MockitoExtension.class)
class WatermarkAdvanceStepListenerTest {

    @Mock
    private BatchWatermarkMapper batchWatermarkMapper;

    private WatermarkAdvanceStepListener listener;

    @BeforeEach
    void setUp() {
        listener = new WatermarkAdvanceStepListener(batchWatermarkMapper);
    }

    private StepExecution stepExecution(OffsetDateTime runStartedAt, Long validationRunId) {
        JobExecution jobExecution = new JobExecution(
                new JobInstance(1L, DailyChangedContractJobNames.JOB_NAME), new JobParameters());
        if (runStartedAt != null) {
            jobExecution.getExecutionContext().putString("runStartedAt", runStartedAt.toString());
        }
        if (validationRunId != null) {
            jobExecution.getExecutionContext().putLong("validationRunId", validationRunId);
        }
        StepExecution stepExecution = new StepExecution("changedContractStep", jobExecution);
        stepExecution.setReadCount(5);
        return stepExecution;
    }

    @Test
    void completedStepAdvancesWatermarkToRunStartedAt() {
        OffsetDateTime runStartedAt = OffsetDateTime.parse("2026-08-11T02:00:00+09:00");
        StepExecution stepExecution = stepExecution(runStartedAt, 777L);
        stepExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterStep(stepExecution);

        verify(batchWatermarkMapper).advance(
                eq(DailyChangedContractJobNames.JOB_NAME), eq(runStartedAt), eq(777L), eq(5L));
    }

    @Test
    void failedStepDoesNotAdvanceWatermark() {
        StepExecution stepExecution = stepExecution(OffsetDateTime.now(), 777L);
        stepExecution.setStatus(BatchStatus.FAILED);

        listener.afterStep(stepExecution);

        verifyNoInteractions(batchWatermarkMapper);
    }

    @Test
    void missingRunStartedAtSkipsAdvanceEvenIfCompleted() {
        StepExecution stepExecution = stepExecution(null, 777L);
        stepExecution.setStatus(BatchStatus.COMPLETED);

        listener.afterStep(stepExecution);

        verify(batchWatermarkMapper, never()).advance(any(), any(), any(), org.mockito.ArgumentMatchers.anyLong());
    }
}
