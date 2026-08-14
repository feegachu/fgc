package com.susukkang.fgc.validation.batch;

import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.mapper.ValidationRunMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.Job;
import org.springframework.batch.core.job.SimpleJob;
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
        createdValidationRunIds.forEach(id -> {
            jdbcTemplate.update(
                    "DELETE FROM fgc.validation_target WHERE validation_run_id = ?", id);
            jdbcTemplate.update(
                    "DELETE FROM fgc.validation_run WHERE validation_run_id = ?", id);
        });
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
    void configuresMasterStepsInTheRequiredOrder() {
        assertThat(monthlyValidationJob).isInstanceOf(SimpleJob.class);
        assertThat(((SimpleJob) monthlyValidationJob).getStepNames()).containsExactly(
                "createRunStep", "selectTargetStep", "regenerateScheduleStep",
                "capCheckStep", "arbitrageCheckStep", "journalPostingStep",
                "imbalanceCheckStep", "reconciliationStep", "exceptionGenerationStep");
    }

    @Test
    /**
     * @author hjKang
     * @since 2026-08-13
     *
     * 2026-08-13 - 대상 선별 Step 구현에 따른 월 통합검증 실행 흐름 검증 변경
     * 기존 코드: createRunStep 완료 후 selectTargetStep의 Placeholder에서 실행이 실패했다.
     * 문제: selectTargetStep이 실제 구현으로 교체되어 기존 완료 Step 기대값과 일치하지 않았다.
     * 개선: 대상 선별 Step 완료 후 다음 미구현 Step에서 실행이 차단되는지 검증한다.
     */
    void completesImplementedStepsAndBlocksAtJournalPlaceholder() throws Exception {
        jobLauncherTestUtils.setJob(monthlyValidationJob);
        long runNo = ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE);

        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters("req-1", runNo));

        Long validationRunId = ValidationRunBatchContext.getValidationRunId(jobExecution.getExecutionContext());
        assertThat(validationRunId).isNotNull();
        createdValidationRunIds.add(validationRunId);

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.FAILED);

        Set<String> completedStepNames = jobExecution.getStepExecutions().stream()
                .filter(se -> se.getStatus() == BatchStatus.COMPLETED)
                .map(StepExecution::getStepName)
                .collect(Collectors.toSet());

        assertThat(completedStepNames).contains(
                "createRunStep",
                "selectTargetStep",
                "regenerateScheduleStep",
                "capCheckStep",
                "arbitrageCheckStep"
        );

        // capCheckStep은 INSURER_TO_GA/GA_TO_FC 2개 파티션 워커로 나뉘어 실행돼야 한다.
        long capCheckWorkerCount = jobExecution.getStepExecutions().stream()
                .filter(se -> se.getStepName().startsWith("capCheckWorkerStep"))
                .count();
        assertThat(capCheckWorkerCount).isEqualTo(2);

        assertThat(jobExecution.getStepExecutions())
                .filteredOn(step -> step.getStatus() == BatchStatus.FAILED)
                .extracting(StepExecution::getStepName)
                .containsExactly("journalPostingStep");

        ValidationRunRow row = validationRunMapper.findById(validationRunId);
        assertThat(row.getStatus()).isEqualTo("FAILED");
        assertThat(row.getValidationMonth()).isEqualTo(TEST_MONTH);
        assertThat(row.getRunNo()).isEqualTo(Math.toIntExact(runNo));
        assertThat(row.getStartedAt()).isNotNull();
        assertThat(row.getCompletedAt()).isNull();
    }

    @Test
    /**
     * @author hjKang
     * @since 2026-08-13
     *
     * 2026-08-13 - 잘못된 파라미터 실행의 데이터 생성 여부 검증 범위 보완
     * 기존 코드: 동일 검증월의 validation_run 전체가 비어 있는지 확인했다.
     * 문제: 다른 테스트나 기존 데이터가 같은 검증월을 사용하면 테스트가 독립적으로 동작하지 않았다.
     * 개선: 이번 테스트에서 사용한 고유 실행 번호에 해당하는 행만 생성되지 않았는지 확인한다.
     */
    void rejectsInvalidJobParametersBeforeCreatingAnyRun() {
        jobLauncherTestUtils.setJob(monthlyValidationJob);
        long runNo = ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE);
        JobParameters invalidParams = new JobParametersBuilder()
                .addString("validationMonth", "2031/03")
                .addLong("runNo", runNo)
                .addString("runType", "MONTHLY")
                .addLong("triggeredBy", 3L)
                .addString("requestId", "req-invalid")
                .toJobParameters();

        org.junit.jupiter.api.Assertions.assertThrows(
                org.springframework.batch.core.JobParametersInvalidException.class,
                () -> jobLauncherTestUtils.launchJob(invalidParams));

        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT 1 FROM fgc.validation_run WHERE validation_month = ? AND run_no = ?",
                TEST_MONTH,
                runNo);
        assertThat(rows).isEmpty();
    }
}
