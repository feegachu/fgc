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
 * ①대상월 실행 생성(IF-BAT-01 Step 1). JobParameters → validation_run INSERT는 이미
 * ValidationRunCreateService(#32)가 구현해 뒀으므로 그대로 재사용한다 — 즉시 재계산(①모드)이든
 * 월 배치(④모드)든 "실행 생성" 자체는 같은 서비스를 거쳐야 uq_validation_run_active_month 같은
 * 무결성 규칙이 한 곳에서만 지켜진다.
 *
 * 생성된 validationRunId는 이 Step 이후에도 계속 필요해서(②~⑧이 전부 이 값을 참조) Job의
 * ExecutionContext에 심어 둔다 — ValidationRunBatchContext 참고.
 */
@RequiredArgsConstructor
public class CreateRunTasklet implements Tasklet {

    private final ValidationRunCreateService validationRunCreateService;

    /**
     * Tasklet.execute()는 Spring Batch가 이 Step을 실행할 때 부르는 진입점이다. Chunk
     * 방식(대량 데이터를 read→process→write로 나눠 여러 번 반복)과 달리 Tasklet은 "한 번
     * 호출하고 끝"이라 로직이 read/write 콜백으로 쪼개지지 않고 여기 한 메서드 안에 전부 있다.
     * 반환값 RepeatStatus.FINISHED는 "이 Step 할 일 다 했다"는 뜻 — CONTINUABLE을 돌려주면
     * Spring Batch가 execute()를 다시 호출하는데, 우리는 반복이 필요 없어 항상 FINISHED다.
     */
    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) {
        // 1) MonthlyValidationJobParametersValidator가 이미 Job 시작 전에 형식을 검증해 뒀으므로,
        //    여기서는 안심하고 JobParameters를 그대로 파싱한다. chunkContext를 타고 들어가는
        //    경로(getStepContext().getStepExecution().getJobParameters())가 Tasklet 안에서
        //    JobParameters를 얻는 표준적인 방법이다.
        JobParameters jobParameters = chunkContext.getStepContext().getStepExecution()
                .getJobParameters();
        MonthlyValidationJobParameters params = MonthlyValidationJobParameters.from(jobParameters);

        // 2) 실제 생성은 전부 ValidationRunCreateService에 위임한다. 월 배치의 runNo는 Spring
        //    Batch JobInstance 식별자일 뿐 아니라 validation_run 복합 유니크 키의 두 번째 값이다.
        //    따라서 서비스가 다시 채번하지 않고 JobParameters 값을 그대로 저장해야 실행 이력과
        //    Batch 메타데이터가 같은 회차를 가리킨다.
        CreateValidationRunCommand command = new CreateValidationRunCommand(
                params.validationMonth(), params.runType(), params.triggeredBy(), Math.toIntExact(params.runNo()));
        ValidationRunRow created = validationRunCreateService.create(command);

        // 3) 방금 생성된 PK를 Job의 ExecutionContext에 심는다 — 이 Step이 끝나고 나면
        //    ValidationRunStepProgressListener가 이 값을 읽어 CREATED→RUNNING 전이를 하고,
        //    이후 ②~⑧ Step들도 전부 이 값을 참조한다(ValidationRunBatchContext 참고).
        //    create()가 실패하면(예: 활성 MONTHLY 실행 중복) 이 줄까지 오지 않고 예외가
        //    던져지므로, 그 경우 컨텍스트에는 아무것도 안 남는다 — 나중에 리스너가
        //    "validationRunId가 없다"로 정상적으로 감지한다.
        ValidationRunBatchContext.putValidationRunId(chunkContext, created.getValidationRunId());

        return RepeatStatus.FINISHED;
    }
}
