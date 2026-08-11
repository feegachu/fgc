package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.JobExecution;
import org.springframework.batch.core.JobParameters;
import org.springframework.batch.core.JobParametersBuilder;
import org.springframework.batch.core.StepExecution;
import org.springframework.batch.test.JobLauncherTestUtils;
import org.springframework.batch.test.context.SpringBatchTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IF-BAT-01 MonthlyValidationJob(#57) 골격 통합테스트. 실제 JobRepository/DB로 Job 전체를
 * 한 번 돌려서 "①createRunStep은 정상 완료되고, 아직 실제 포트 구현이 없는 ②~⑧ 자리에서
 * Job이 FAILED로 멈추며 validation_run도 FAILED로 남는지"를 검증한다(#60에서 각 Step이
 * MonthlyValidationStepCoordinator의 포트를 실제 구현으로 갈아끼우면, 이 테스트도 정상 완료
 * 경로(COMPLETED)를 검증하도록 갱신해야 한다). 지금 이 테스트는 로직의 정확성이 아니라
 * 배선(순서·파티션·진행상황 기록·실패 전파)이 맞는지만 본다.
 *
 * @Transactional을 안 쓴다: Spring Batch가 Step마다 자기 트랜잭션을 커밋해야 JobRepository가
 * 다음 Step에서 이전 상태를 볼 수 있다 — 테스트를 하나의 롤백 트랜잭션으로 감싸면 그 커밋이
 * 막혀 배치가 정상 동작하지 않는다. 대신 @AfterEach에서 생성된 행을 직접 지운다.
 */
@SpringBootTest
@SpringBatchTest
class MonthlyValidationJobIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2031, 3, 1);

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private Job monthlyValidationJob;

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

    // JobRepository 메타테이블은 이 테스트가 지우지 않는다(validation_run만 지운다) — 그래서
    // 같은 JobParameters로 두 번 실행하면 이미 이 서버 DB에 남아있는 이전 실행과 충돌해
    // JobInstanceAlreadyCompleteException이 난다. runNo를 매번 새로 뽑아 항상 새 JobInstance가
    // 되게 한다.
    private JobParameters jobParameters(String requestId, long runNo) {
        return new JobParametersBuilder()
                .addString("validationMonth", "2031-03")
                .addLong("runNo", runNo)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 3L)
                .addString("requestId", requestId)
                .toJobParameters();
    }

    @Test
    void blocksPlaceholderExecutionAndDoesNotCompleteTheValidationRun() throws Exception {
        jobLauncherTestUtils.setJob(monthlyValidationJob);
        long runNo = ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE);

        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters("req-1", runNo));

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.FAILED);

        Set<String> completedStepNames = jobExecution.getStepExecutions().stream()
                .filter(se -> se.getStatus() == BatchStatus.COMPLETED)
                .map(StepExecution::getStepName)
                .collect(Collectors.toSet());

        assertThat(completedStepNames).containsExactly("createRunStep");

        // capCheckStep은 INSURER_TO_GA/GA_TO_FC 2개 파티션 워커로 나뉘어 실행돼야 한다.
        long capCheckWorkerCount = jobExecution.getStepExecutions().stream()
                .filter(se -> se.getStepName().startsWith("capCheckWorkerStep"))
                .count();
        assertThat(capCheckWorkerCount).isZero();

        Long validationRunId = ValidationRunBatchContext.getValidationRunId(jobExecution.getExecutionContext());
        assertThat(validationRunId).isNotNull();
        createdValidationRunIds.add(validationRunId);

        ValidationRunRow row = validationRunMapper.findById(validationRunId);
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getValidationMonth()).isEqualTo(TEST_MONTH);
        assertThat(row.getRunNo()).isEqualTo(Math.toIntExact(runNo));
        assertThat(row.getStartedAt()).isNotNull();
        assertThat(row.getCompletedAt()).isNull();
    }

    @Test
    void rejectsInvalidJobParametersBeforeCreatingAnyRun() {
        jobLauncherTestUtils.setJob(monthlyValidationJob);
        JobParameters invalidParams = new JobParametersBuilder()
                .addString("validationMonth", "2031/03")
                .addLong("runNo", 1L)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 3L)
                .addString("requestId", "req-invalid")
                .toJobParameters();

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.batch.core.JobParametersInvalidException.class,
                () -> jobLauncherTestUtils.launchJob(invalidParams));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT 1 FROM fgc.validation_run WHERE validation_month = ?", TEST_MONTH);
        assertThat(rows).isEmpty();
    }
}
