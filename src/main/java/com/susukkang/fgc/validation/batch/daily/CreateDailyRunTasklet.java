package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.dto.BatchWatermarkRow;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.BatchWatermarkMapper;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import com.susukkang.fgc.validation.service.ValidationRunBatchAuditService;
import com.susukkang.fgc.validation.service.ValidationRunBatchLifecycleService;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import com.susukkang.fgc.validation.service.ValidationRunTransitionService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

import java.time.OffsetDateTime;

/**
 * DailyChangedContractJob의 1번째 Step
 * "오늘 쓸 validation_run 하나를 정한다"가 이 Tasklet의 유일한 책임
 */
@RequiredArgsConstructor
public class CreateDailyRunTasklet implements Tasklet {

    private final ValidationRunMapper validationRunMapper;
    private final ValidationRunCreateService validationRunCreateService;
    private final ValidationRunTransitionService validationRunTransitionService;
    private final ValidationRunBatchLifecycleService lifecycleService;
    private final ValidationRunBatchAuditService auditService;
    private final BatchWatermarkMapper batchWatermarkMapper;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobParameters jobParameters = chunkContext.getStepContext().getStepExecution().getJobParameters();
        MonthlyValidationJobParameters params = MonthlyValidationJobParameters.from(jobParameters);

        // "오늘"을 Asia/Seoul 자정 기준으로 구한다 — 서버 타임존이 달라도 배치 기준시는
        // 항상 서울 자정이어야 "매일 02:00 KST"라는 운영 가정과 어긋나지 않는다.
        OffsetDateTime now = OffsetDateTime.now(DateUtil.SEOUL_ZONE);
        OffsetDateTime dayStart = now.toLocalDate().atStartOfDay(DateUtil.SEOUL_ZONE).toOffsetDateTime();
        OffsetDateTime dayEnd = dayStart.plusDays(1);

        ValidationRunRow existing = validationRunMapper.findManualContractRunCreatedBetween(dayStart, dayEnd);
        Long validationRunId = (existing == null)
                ? createAndStart(params)
                : reuseOrRecreate(existing, params);

        // 이 Step 이후로는 changedContractStep을 포함한 모든 뒤 Step이 이 값을 읽는다.
        ValidationRunBatchContext.putValidationRunId(chunkContext, validationRunId);

        // changedContractStep의 Reader가 "어디서부터 변경분으로 볼지" 기준으로 쓸 watermark를
        // 여기서 미리 읽어 Job ExecutionContext에 박아둔다. V2 마이그레이션이 심어둔 시드 행이
        // 있으므로 findByJobNameAndStepName은 null을 반환하지 않는다(운영 정책서 §9-1: watermark
        // 행은 배치가 최초 실행되기 전에 이미 만들어져 있어야 함). batch_watermark의 PK가
        // (job_name, step_name)이라(V2_1 마이그레이션) 두 값을 모두 넘긴다.
        BatchWatermarkRow watermark = batchWatermarkMapper.findByJobNameAndStepName(
                DailyChangedContractJobNames.JOB_NAME, DailyChangedContractJobNames.CHANGED_CONTRACT_STEP_NAME);
        DailyBatchContext.putLastProcessedAt(chunkContext, watermark.getLastProcessedAt());

        // watermark를 이번 실행이 끝난 뒤 어디로 전진시킬지는 "이 Step이 실행되기 시작한 시각"으로
        // 고정한다 — changedContractStep이 도는 동안 새로 들어오는 이벤트를 "이미 처리했다"고
        // 잘못 표시하지 않기 위함(그 이벤트는 last_processed_at보다 뒤에 남아 다음 날 다시 잡힌다).
        DailyBatchContext.putRunStartedAt(chunkContext, now);

        return RepeatStatus.FINISHED;
    }

    private Long createAndStart(MonthlyValidationJobParameters params) {
        CreateValidationRunCommand command =
                new CreateValidationRunCommand(params.validationMonth(), params.runType(), params.triggeredBy());
        ValidationRunRow created = validationRunCreateService.create(command);
        lifecycleService.start(created.getValidationRunId(), params);
        return created.getValidationRunId();
    }

    private Long reuseOrRecreate(ValidationRunRow existing, MonthlyValidationJobParameters params) {
        ValidationRunStatus status = ValidationRunStatus.valueOf(existing.getStatus());
        return switch (status) {
            // CREATED는 lifecycleService.start()로 보낸다 — transitionToRunning()이 CREATED만
            // 받아주고, current_step=1·started_at·감사로그까지 한 번에 해주기 때문이다.
            // (일반 transitionService.transition()을 썼다면 상태만 RUNNING이 되고 current_step/
            // started_at/감사로그는 비어 있는 채로 남는다.)
            case CREATED -> {
                lifecycleService.start(existing.getValidationRunId(), params);
                yield existing.getValidationRunId();
            }
            // FAILED→RUNNING은 "재시도"다. current_step은 실패 당시 값(1~8)이 이미 있으므로
            // 손댈 게 없고, 상태만 되돌리면 된다 — 그래서 batch 전용 lifecycleService가 아니라
            // 사람이 쓰는 것과 같은 범용 ValidationRunTransitionService로 충분하다.
            case FAILED -> {
                validationRunTransitionService.transition(existing.getValidationRunId(), ValidationRunStatus.RUNNING);
                // 범용 ValidationRunTransitionService는 감사로그를 남기지 않으므로(위 주석),
                // 재시도라는 사실 자체를 여기서 직접 남긴다 — #77 재실행 감사 추적 요구사항.
                auditService.recordRetried(existing.getValidationRunId(), params);
                yield existing.getValidationRunId();
            }
            case RUNNING -> existing.getValidationRunId();
            case COMPLETED, FINALIZED -> createAndStart(params);
        };
    }
}
