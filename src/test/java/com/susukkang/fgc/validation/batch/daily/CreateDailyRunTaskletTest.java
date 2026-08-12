package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.dto.BatchWatermarkRow;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.BatchWatermarkMapper;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import com.susukkang.fgc.validation.service.ValidationRunBatchAuditService;
import com.susukkang.fgc.validation.service.ValidationRunBatchLifecycleService;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import com.susukkang.fgc.validation.service.ValidationRunTransitionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobInstance;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.scope.context.StepContext;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * CreateDailyRunTasklet의 "오늘 실행을 어떻게 정하는가"(ValidationRunStatus별 분기)와
 * watermark/runStartedAt을 Job ExecutionContext에 심는 동작을 검증한다.
 */
@ExtendWith(MockitoExtension.class)
class CreateDailyRunTaskletTest {

    @Mock
    private ValidationRunMapper validationRunMapper;
    @Mock
    private ValidationRunCreateService validationRunCreateService;
    @Mock
    private ValidationRunTransitionService validationRunTransitionService;
    @Mock
    private ValidationRunBatchLifecycleService lifecycleService;
    @Mock
    private ValidationRunBatchAuditService auditService;
    @Mock
    private BatchWatermarkMapper batchWatermarkMapper;

    private CreateDailyRunTasklet tasklet;
    private final OffsetDateTime seededWatermark = OffsetDateTime.parse("2026-07-01T00:00:00+09:00");

    @BeforeEach
    void setUp() {
        tasklet = new CreateDailyRunTasklet(
                validationRunMapper, validationRunCreateService, validationRunTransitionService,
                lifecycleService, auditService, batchWatermarkMapper);

        BatchWatermarkRow watermark = new BatchWatermarkRow();
        watermark.setJobName(DailyChangedContractJobNames.JOB_NAME);
        watermark.setLastProcessedAt(seededWatermark);
        given(batchWatermarkMapper.findByJobNameAndStepName(
                DailyChangedContractJobNames.JOB_NAME, DailyChangedContractJobNames.CHANGED_CONTRACT_STEP_NAME))
                .willReturn(watermark);
    }

    private ChunkContext newChunkContext() {
        JobParameters parameters = new JobParametersBuilder()
                .addString("validationMonth", "2026-08")
                .addLong("runNo", 1L)
                .addString("runType", "MANUAL_CONTRACT")
                .addLong("triggeredBy", 1L)
                .addString("requestId", "req-1")
                .toJobParameters();
        JobExecution jobExecution = new JobExecution(new JobInstance(1L, DailyChangedContractJobNames.JOB_NAME), parameters);
        StepExecution stepExecution = new StepExecution("createDailyRunStep", jobExecution);
        return new ChunkContext(new StepContext(stepExecution));
    }

    private ValidationRunRow runWithStatus(ValidationRunStatus status) {
        ValidationRunRow row = new ValidationRunRow();
        row.setValidationRunId(42L);
        row.setStatus(status.name());
        return row;
    }

    @Test
    void noExistingRunTodayCreatesAndStartsNewRun() {
        given(validationRunMapper.findManualContractRunCreatedBetween(any(), any())).willReturn(null);
        ValidationRunRow created = runWithStatus(ValidationRunStatus.CREATED);
        given(validationRunCreateService.create(any())).willReturn(created);

        ChunkContext chunkContext = newChunkContext();
        RepeatStatus result = tasklet.execute(null, chunkContext);

        assertThat(result).isEqualTo(RepeatStatus.FINISHED);
        verify(lifecycleService).start(anyLong(), any());
        assertThat(ValidationRunBatchContext.getValidationRunId(chunkContext)).isEqualTo(42L);
    }

    @Test
    void existingCreatedRunIsStarted() {
        given(validationRunMapper.findManualContractRunCreatedBetween(any(), any()))
                .willReturn(runWithStatus(ValidationRunStatus.CREATED));

        tasklet.execute(null, newChunkContext());

        verify(lifecycleService).start(eq(42L), any());
        verify(validationRunCreateService, never()).create(any());
    }

    @Test
    void existingFailedRunIsRetriedViaTransition() {
        given(validationRunMapper.findManualContractRunCreatedBetween(any(), any()))
                .willReturn(runWithStatus(ValidationRunStatus.FAILED));

        tasklet.execute(null, newChunkContext());

        verify(validationRunTransitionService).transition(42L, ValidationRunStatus.RUNNING);
        verify(lifecycleService, never()).start(anyLong(), any());
        // #77: 범용 transition()은 감사로그를 안 남기므로, 재시도 사실 자체는 Tasklet이 직접
        // 남겨야 한다 — 안 남기면 "재실행 이력이 감사 로그에 없다"는 요구사항 위반이 된다.
        verify(auditService).recordRetried(eq(42L), any());
    }

    @Test
    void existingRunningRunIsReusedAsIs() {
        given(validationRunMapper.findManualContractRunCreatedBetween(any(), any()))
                .willReturn(runWithStatus(ValidationRunStatus.RUNNING));

        ChunkContext chunkContext = newChunkContext();
        tasklet.execute(null, chunkContext);

        verify(lifecycleService, never()).start(anyLong(), any());
        verify(validationRunTransitionService, never()).transition(anyLong(), any());
        assertThat(ValidationRunBatchContext.getValidationRunId(chunkContext)).isEqualTo(42L);
    }

    @Test
    void existingCompletedRunCausesFreshRunToBeCreated() {
        given(validationRunMapper.findManualContractRunCreatedBetween(any(), any()))
                .willReturn(runWithStatus(ValidationRunStatus.COMPLETED));
        ValidationRunRow freshRun = runWithStatus(ValidationRunStatus.CREATED);
        freshRun.setValidationRunId(99L);
        given(validationRunCreateService.create(any())).willReturn(freshRun);

        ChunkContext chunkContext = newChunkContext();
        tasklet.execute(null, chunkContext);

        verify(lifecycleService).start(eq(99L), any());
        assertThat(ValidationRunBatchContext.getValidationRunId(chunkContext)).isEqualTo(99L);
    }

    // 운영정책서(docs/FGC_가상_GA_운영정책서_v1_0.md)의 "FINALIZED 결과는 절대 덮어쓰지 않는다"
    // 원칙을 이 배치에서도 지키는지 확인한다 — reuseOrRecreate의 switch가 COMPLETED와
    // FINALIZED를 같은 분기(createAndStart)로 묶어놓았는데, 누군가 나중에 이 switch를
    // 나누면서 FINALIZED 케이스를 실수로 빠뜨리는 회귀를 잡기 위해 별도로 고정해 둔다.
    @Test
    void existingFinalizedRunCausesFreshRunToBeCreatedWithoutTouchingTheFinalizedRow() {
        given(validationRunMapper.findManualContractRunCreatedBetween(any(), any()))
                .willReturn(runWithStatus(ValidationRunStatus.FINALIZED));
        ValidationRunRow freshRun = runWithStatus(ValidationRunStatus.CREATED);
        freshRun.setValidationRunId(77L);
        given(validationRunCreateService.create(any())).willReturn(freshRun);

        ChunkContext chunkContext = newChunkContext();
        tasklet.execute(null, chunkContext);

        // FINALIZED였던 기존 실행(id=42)에는 어떤 전이 호출도 가지 않는다 — 새 실행(77)만 시작된다.
        verify(lifecycleService).start(eq(77L), any());
        verify(lifecycleService, never()).start(eq(42L), any());
        verify(validationRunTransitionService, never()).transition(anyLong(), any());
        assertThat(ValidationRunBatchContext.getValidationRunId(chunkContext)).isEqualTo(77L);
    }

    @Test
    void watermarkAndRunStartedAtAreStoredInJobExecutionContext() {
        given(validationRunMapper.findManualContractRunCreatedBetween(any(), any()))
                .willReturn(runWithStatus(ValidationRunStatus.RUNNING));

        ChunkContext chunkContext = newChunkContext();
        tasklet.execute(null, chunkContext);

        var jobExecutionContext = chunkContext.getStepContext().getStepExecution()
                .getJobExecution().getExecutionContext();
        assertThat(DailyBatchContext.getLastProcessedAt(jobExecutionContext)).isEqualTo(seededWatermark);
        assertThat(DailyBatchContext.getRunStartedAt(jobExecutionContext)).isNotNull();
    }
}
