package com.susukkang.fgc.validation.batch.tasklet;

import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.MonthlyValidationJobParameters;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.service.ValidationRunCreateService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.StepContribution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.repeat.RepeatStatus;

/**
 * 대상월 실행 생성(IF-BAT-01 Step 1)
 */
@RequiredArgsConstructor
public class CreateRunTasklet implements Tasklet {

    private final ValidationRunCreateService validationRunCreateService;

    /**
     * Tasklet.execute(): Spring Batch가 이 Step을 실행할 때 부르는 진입점
     */
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // 1) MonthlyValidationJobParametersValidator가 이미 Job 시작 전에 형식을 검증해 뒀으므로, JobParameters를 그대로 파싱
        JobParameters jobParameters = chunkContext.getStepContext().getStepExecution()
                .getJobParameters();
        MonthlyValidationJobParameters params = MonthlyValidationJobParameters.from(jobParameters);

        // 2) 실제 생성은 전부 ValidationRunCreateService에 위임
        CreateValidationRunCommand command = new CreateValidationRunCommand(
                params.validationMonth(), params.runType(), params.triggeredBy(), Math.toIntExact(params.runNo()));
        ValidationRunRow created = validationRunCreateService.create(command);

        // 3) 방금 생성된 PK를 Job의 ExecutionContext에 심음
        ValidationRunBatchContext.putValidationRunId(chunkContext, created.getValidationRunId());

        return RepeatStatus.FINISHED;
    }
}
