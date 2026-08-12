package com.susukkang.fgc.validation.batch.daily;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.validation.batch.ValidationRunBatchContext;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 코드리뷰(2026-08-11)로 발견된 치명적 버그 — createDailyRunStep에 붙어 있던
 * ValidationRunStepProgressListener(1, true)가 CreateDailyRunTasklet이 이미 RUNNING으로
 * 바꿔 놓은 validation_run에 대해 lifecycleService.start()를 한 번 더 호출해 VRUN_005로
 * 매 실행 실패하던 문제 — 의 회귀를 잡기 위한 통합테스트. 실제 JobRepository/DB로 Job
 * 전체를 한 번 돌려서 "①createDailyRunStep이 COMPLETED로 끝나고, ②changedContractStep으로
 * 실제로 넘어가는지"를 검증한다. changedContractStep 자체의 업무 로직 성공 여부(실 데이터
 * 유무에 따라 갈림)까지는 이 테스트의 관심사가 아니다 — 그건 ChangedContractItem* 단위테스트가
 * 이미 다루고 있다.
 */
@SpringBootTest
@SpringBatchTest
class DailyChangedContractJobIntegrationTest {

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    @Qualifier("dailyChangedContractJob")
    private Job dailyChangedContractJob;

    @Autowired
    private ValidationRunMapper validationRunMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdValidationRunIds = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        createdValidationRunIds.forEach(id -> jdbcTemplate.update(
                "DELETE FROM fgc.validation_run WHERE validation_run_id = ?", id));
    }

    // MonthlyValidationJobIntegrationTest와 같은 이유로 매번 새 JobInstance가 되도록 requestId를
    // 무작위로 바꾼다. validationMonth는 CreateDailyRunTasklet이 실제로 쓰지 않는 필드지만
    // MonthlyValidationJobParameters 파싱 규칙(yyyy-MM, STRICT)은 통과해야 한다.
    private JobParameters jobParameters(String requestId) {
        return new JobParametersBuilder()
                .addString("validationMonth", "2031-03")
                .addLong("runNo", 1L)
                .addString("runType", "MANUAL_CONTRACT")
                .addLong("triggeredBy", 3L)
                .addString("requestId", requestId)
                .toJobParameters();
    }

    @Test
    void createDailyRunStepCompletesAndJobProceedsToChangedContractStep() throws Exception {
        jobLauncherTestUtils.setJob(dailyChangedContractJob);
        String requestId = "req-" + ThreadLocalRandom.current().nextLong();

        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters(requestId));

        StepExecution createDailyRunStepExecution = jobExecution.getStepExecutions().stream()
                .filter(se -> se.getStepName().equals("createDailyRunStep"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("createDailyRunStep이 실행되지 않았다"));

        // 이 assertion이 바로 회귀 방지 포인트: 고쳐지지 않았다면 이중 start() 호출로
        // ExitStatus.FAILED가 되어 이 assertion이 실패한다.
        assertThat(createDailyRunStepExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(createDailyRunStepExecution.getExitStatus().getExitCode())
                .isEqualTo(BatchStatus.COMPLETED.name());

        Set<String> executedStepNames = jobExecution.getStepExecutions().stream()
                .map(StepExecution::getStepName)
                .collect(Collectors.toSet());
        assertThat(executedStepNames).contains("changedContractStep");

        // 시간창 기반 조회(created_at >= now() - interval)는 같은 DB를 공유하는 다른
        // 테스트/실행이 만든 행까지 주워서 hasSize(1) 검증이 흔들리고, cleanUp이 남의 행까지
        // 지울 위험이 있다(코드리뷰 지적, 2026-08-11) — CreateDailyRunTasklet이 Job
        // ExecutionContext에 정확히 심어 둔 validationRunId를 그대로 읽는다.
        Long validationRunId = ValidationRunBatchContext.getValidationRunId(jobExecution.getExecutionContext());
        assertThat(validationRunId).isNotNull();
        createdValidationRunIds.add(validationRunId);

        ValidationRunRow row = validationRunMapper.findById(validationRunId);
        assertThat(row.getStatus()).isIn(
                ValidationRunStatus.RUNNING.name(),
                ValidationRunStatus.COMPLETED.name(),
                ValidationRunStatus.FAILED.name());
    }
}
