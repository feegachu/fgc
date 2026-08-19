package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.batch.contract.JournalPostingPort;
import com.susukkang.fgc.validation.batch.contract.JournalPostingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * Step 6a: IF-BAT-01 "⑥검증원장 기표". JournalPostingPort.post()를 호출해 이번 검증
 * 실행 범위의 분개를 생성·기표한다. 실패는(개별 원천 포함) catch하지 않고 그대로
 * 전파해 Step을 실패시킨다(06_배치_Step_협업계약.md:18 "기표 오류는 Step 실패").
 */
@Slf4j
@RequiredArgsConstructor
public class JournalPostingTasklet implements Tasklet {

    private final JournalPostingPort journalPostingPort;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        JobParameters jobParameters = chunkContext.getStepContext().getStepExecution().getJobParameters();
        MonthlyValidationJobParameters params = MonthlyValidationJobParameters.from(jobParameters);

        Long validationRunId = ValidationRunBatchContext.getValidationRunId(chunkContext);

        ValidationJobContext job = new ValidationJobContext(
                params.validationMonth(), params.runNo(), params.runType(),
                params.triggeredBy(), params.requestId());
        ValidationStepContext stepContext = new ValidationStepContext(validationRunId, job);

        JournalPostingResult result = journalPostingPort.post(stepContext);
        contribution.incrementWriteCount(result.postedJournalCount());
        log.info("[validationRunId={}] journalPostingStep 완료: posted={}, skipped={}, failed={}",
                validationRunId, result.postedJournalCount(), result.skippedCount(), result.failureCount());

        return RepeatStatus.FINISHED;
    }
}
