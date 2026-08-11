package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.mapper.BatchWatermarkMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.core.StepExecutionListener;
import org.springframework.batch.item.ExecutionContext;

import java.time.OffsetDateTime;

/**
 * changedContractStep 전용: Step이 온전히 성공했을 때만 fgc.batch_watermark를 전진
 *
 * "온전히"의 기준이 BatchStatus.COMPLETED인 이유: 이 Job은 Step 안에서 Processor가 던진
 * FgcBusinessException을 삼켜 데이터 품질 스킵으로 바꾸므로, 스킵된 계약이 있어도 Step 자체는
 * COMPLETED로 끝남
 */
@Slf4j
@RequiredArgsConstructor
public class WatermarkAdvanceStepListener implements StepExecutionListener {

    private final BatchWatermarkMapper batchWatermarkMapper;

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        if (stepExecution.getStatus() != BatchStatus.COMPLETED) {
            log.info("changedContractStep이 {}로 끝나 watermark를 전진시키지 않습니다.", stepExecution.getStatus());
            return stepExecution.getExitStatus();
        }

        ExecutionContext jobExecutionContext = stepExecution.getJobExecution().getExecutionContext();
        OffsetDateTime runStartedAt = DailyBatchContext.getRunStartedAt(jobExecutionContext);
        Long validationRunId = ValidationRunBatchContext.getValidationRunId(jobExecutionContext);

        if (runStartedAt == null) {
            // createDailyRunStep 배선 오류 — 정상 흐름에서는 일어날 수 없다.
            log.warn("runStartedAt이 없어 watermark를 전진시키지 못했습니다. createDailyRunStep 배선을 확인하세요.");
            return stepExecution.getExitStatus();
        }

        batchWatermarkMapper.advance(
                DailyChangedContractJobNames.JOB_NAME,
                runStartedAt,
                validationRunId,
                stepExecution.getReadCount());

        return stepExecution.getExitStatus();
    }
}
