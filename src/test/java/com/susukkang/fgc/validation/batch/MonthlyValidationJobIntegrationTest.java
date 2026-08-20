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
 * 한 번 돌려서 "9개 Step이 순서대로·파티션까지 정상 배선돼 있고, 대상 데이터가 없는 달에는
 * 전부 정상 완료되어 validation_run도 COMPLETED로 남는지"를 검증한다(#60에서 모든 Step의
 * 포트가 실제 구현으로 교체되며 갱신됨 — 그 전에는 미구현 Placeholder Step에서 실패로
 * 멈추는 경로를 검증했다). 지금 이 테스트는 로직의 정확성이 아니라 배선(순서·파티션·
 * 진행상황 기록·완료 전파)이 맞는지만 본다.
 *
 * @Transactional을 안 쓴다: Spring Batch가 Step마다 자기 트랜잭션을 커밋해야 JobRepository가
 * 다음 Step에서 이전 상태를 볼 수 있다 — 테스트를 하나의 롤백 트랜잭션으로 감싸면 그 커밋이
 * 막혀 배치가 정상 동작하지 않는다. 대신 @AfterEach에서 생성된 행을 직접 지운다.
 */
@SpringBootTest
@SpringBatchTest
class MonthlyValidationJobIntegrationTest {

    // 시드 계약보다 앞선 월을 써야 한다. 이 테스트의 목적은 배치 Step 배선·완료 전파 검증이며,
    // 실제 계약을 선택하면 스케줄이 확정/대사 이력으로 잠겨 @AfterEach에서 안전하게 되돌릴 수 없다.
    private static final LocalDate TEST_MONTH = LocalDate.of(2000, 3, 1);

    @Autowired
    private JobLauncherTestUtils jobLauncherTestUtils;

    @Autowired
    private Job monthlyValidationJob;

    @Autowired
    private ValidationRunMapper validationRunMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<Long> createdValidationRunIds = new ArrayList<>();

    // 2026-08-17 - 대상 선별 상품코드 비교 수정에 따른 정리 범위 확장
    // 기존 코드: cap_check(_detail)·validation_target·validation_run만 지웠다.
    // 문제: 선별 SQL이 insurer_product_code와 비교하던 버그로 전 계약이 REVIEW_REQUIRED가 되어
    //       하위 Step이 결과를 만들지 않았기에 그 정리로 충분했지만, 표준상품코드 비교로 고치자
    //       실제로 대상이 선정되어 arbitrage_check·journal·reconciliation·exception_case가
    //       생성되고 validation_run DELETE가 FK 위반으로 실패했다.
    // 개선: validation_run을 참조하는 자식(손자 포함)을 FK 순서대로 전부 지운다.
    //       스케줄은 실행 FK가 없고 확정·조정 회차는 DB 트리거로 불변이다. 이 테스트는 대상 계약이
    //       없는 검증월을 사용하므로 스케줄을 만들지 않으며, 실행에 종속된 산출물만 정리한다.
    @AfterEach
    void cleanUp() {
        createdValidationRunIds.forEach(id -> {
            jdbcTemplate.execute(
                    "ALTER TABLE fgc.exception_occurrence DISABLE TRIGGER trg_exception_occurrence_append_only");
            try {
                jdbcTemplate.update(
                        "DELETE FROM fgc.exception_occurrence WHERE validation_run_id = ?", id);
            } finally {
                jdbcTemplate.execute(
                        "ALTER TABLE fgc.exception_occurrence ENABLE TRIGGER trg_exception_occurrence_append_only");
            }
            jdbcTemplate.update("""
                    DELETE FROM fgc.exception_action
                     WHERE exception_case_id IN (
                         SELECT exception_case_id FROM fgc.exception_case WHERE validation_run_id = ?
                     )
                    """, id);
            // exception_occurrence는 append-only(trg_exception_occurrence_append_only가
            // UPDATE/DELETE를 항상 거절한다, V23_1) — 테스트 정리 목적으로만 트리거를
            // 잠깐 끈다.
            jdbcTemplate.execute("ALTER TABLE fgc.exception_occurrence DISABLE TRIGGER trg_exception_occurrence_append_only");
            jdbcTemplate.update("""
                    DELETE FROM fgc.exception_occurrence
                     WHERE exception_case_id IN (
                         SELECT exception_case_id FROM fgc.exception_case WHERE validation_run_id = ?
                     )
                    """, id);
            jdbcTemplate.execute("ALTER TABLE fgc.exception_occurrence ENABLE TRIGGER trg_exception_occurrence_append_only");
            jdbcTemplate.update(
                    "DELETE FROM fgc.exception_case WHERE validation_run_id = ?", id);
            jdbcTemplate.update("""
                    DELETE FROM fgc.reconciliation_match
                     WHERE reconciliation_result_id IN (
                         SELECT rr.reconciliation_result_id
                           FROM fgc.reconciliation_result rr
                           JOIN fgc.reconciliation_run r ON r.reconciliation_run_id = rr.reconciliation_run_id
                          WHERE r.validation_run_id = ?
                     )
                    """, id);
            jdbcTemplate.update("""
                    DELETE FROM fgc.reconciliation_result
                     WHERE reconciliation_run_id IN (
                         SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE validation_run_id = ?
                     )
                    """, id);
            jdbcTemplate.update(
                    "DELETE FROM fgc.reconciliation_run WHERE validation_run_id = ?", id);
            jdbcTemplate.update("""
                    DELETE FROM fgc.journal_line
                     WHERE journal_header_id IN (
                         SELECT journal_header_id FROM fgc.journal_header WHERE validation_run_id = ?
                     )
                    """, id);
            jdbcTemplate.update(
                    "DELETE FROM fgc.journal_header WHERE validation_run_id = ?", id);
            jdbcTemplate.update("""
                    DELETE FROM fgc.cap_check_detail
                     WHERE cap_check_id IN (
                         SELECT cap_check_id
                           FROM fgc.cap_check
                          WHERE validation_run_id = ?
                     )
                    """, id);
            jdbcTemplate.update(
                    "DELETE FROM fgc.cap_check WHERE validation_run_id = ?", id);
            jdbcTemplate.update(
                    "DELETE FROM fgc.arbitrage_check WHERE validation_run_id = ?", id);
            jdbcTemplate.update(
                    "DELETE FROM fgc.acquisition_cost_check WHERE validation_run_id = ?", id);
            jdbcTemplate.update(
                    "DELETE FROM fgc.maintenance_check WHERE validation_run_id = ?", id);
            jdbcTemplate.update(
                    "DELETE FROM fgc.contract_status_event_processing WHERE validation_run_id = ?", id);
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
                .addString("validationMonth", "2000-03")
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
     * 2026-08-16 - #142 reconciliationStep·exceptionGenerationStep 구현에 따른 완료 경로 변경
     * 기존 코드: journalPostingStep·imbalanceCheckStep 완료 후 미구현 Placeholder였던
     *       reconciliationStep에서 실행이 실패하는 것을 검증했다.
     * 문제: develop 병합으로 reconciliationStep(ReconciliationTasklet)·exceptionGenerationStep
     *       (ExceptionGenerationTasklet)이 모두 실제 구현으로 교체돼 더 이상 무조건 실패하지
     *       않는다. TEST_MONTH(2031-03)에는 대상 데이터가 없어 두 Step 모두 0건 처리로 정상
     *       완료되고, Job 전체가 COMPLETED로 끝난다.
     * 개선: 9개 Step 전부가 COMPLETED로 끝나고 validation_run도 COMPLETED로 전이하는 정상
     *       완료 경로를 검증하도록 갱신한다(클래스 Javadoc에서 예고한 #60 갱신 지점).
     *
     * 2026-08-17 - 대상 선별(FUN-042) 상품코드 비교 수정에 따른 전제 갱신
     * 기존 코드: "TEST_MONTH에는 대상 데이터가 없다"는 전제로 0건 완료 경로를 검증했다.
     * 문제: 그 0건은 실은 선별 SQL이 insurer_product_code와 비교하던 버그로 전 계약이
     *       REVIEW_REQUIRED가 된 결과였다.
     * 개선: 이 테스트는 배치 Step의 배선·완료 전파만 검증하므로, 시드 계약보다 앞선 검증월을
     *       사용해 스케줄·대사 이력을 남기지 않는다. 실제 대상 선별 및 산출물 검증은 각 도메인
     *       통합 테스트가 담당한다.
     */
    void completesAllStepsAgainstSeedData() throws Exception {
        jobLauncherTestUtils.setJob(monthlyValidationJob);
        long runNo = ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE);

        JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters("req-1", runNo));

        Long validationRunId = ValidationRunBatchContext.getValidationRunId(jobExecution.getExecutionContext());
        assertThat(validationRunId).isNotNull();
        createdValidationRunIds.add(validationRunId);

        assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);

        Set<String> completedStepNames = jobExecution.getStepExecutions().stream()
                .filter(se -> se.getStatus() == BatchStatus.COMPLETED)
                .map(StepExecution::getStepName)
                .collect(Collectors.toSet());

        assertThat(completedStepNames).contains(
                "createRunStep",
                "selectTargetStep",
                "regenerateScheduleStep",
                "capCheckStep",
                "arbitrageCheckStep",
                "journalPostingStep",
                "imbalanceCheckStep",
                "reconciliationStep",
                "exceptionGenerationStep"
        );

        // capCheckStep은 INSURER_TO_GA/GA_TO_FC 2개 파티션 워커로 나뉘어 실행돼야 한다.
        long capCheckWorkerCount = jobExecution.getStepExecutions().stream()
                .filter(se -> se.getStepName().startsWith("capCheckWorkerStep"))
                .count();
        assertThat(capCheckWorkerCount).isEqualTo(2);

        assertThat(jobExecution.getStepExecutions())
                .filteredOn(step -> step.getStatus() == BatchStatus.FAILED)
                .isEmpty();

        ValidationRunRow row = validationRunMapper.findById(validationRunId);
        assertThat(row.getStatus()).isEqualTo("COMPLETED");
        assertThat(row.getValidationMonth()).isEqualTo(TEST_MONTH);
        assertThat(row.getRunNo()).isEqualTo(Math.toIntExact(runNo));
        assertThat(row.getStartedAt()).isNotNull();
        assertThat(row.getCompletedAt()).isNotNull();

        // 이 클래스는 실행 배선 테스트다. 과거 검증월에는 대상 계약이 없어야 하며,
        // 그럼에도 9개 Step이 모두 정상 완료되어야 한다.
        long selectedTargets = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.validation_target WHERE validation_run_id = ? AND selection_status = 'SELECTED'",
                Long.class, validationRunId);
        assertThat(selectedTargets).isZero();
        long reconciliationRuns = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.reconciliation_run WHERE validation_run_id = ?",
                Long.class, validationRunId);
        assertThat(reconciliationRuns).isZero();
        long arbitrageChecks = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.arbitrage_check WHERE validation_run_id = ?",
                Long.class, validationRunId);
        assertThat(arbitrageChecks).isZero();
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

    /**
     * TER-006 안정화 DoD — 핵심 배치 실행이 연속 3회 모두 성공해야 한다.
     * 실제 계약이 걸린 월을 쓰면 클래스 Javadoc에 적힌 이유(스케줄 확정·대사 이력으로 잠겨
     * 롤백 불가)로 안전하게 반복할 수 없어, 이 테스트는 completesAllStepsAgainstSeedData와
     * 같은 대상 없음(TEST_MONTH) 시나리오로 "매번 새 JobInstance로 9개 Step이 전부 COMPLETED,
     * 실행끼리 산출물이 섞이지 않음, 잔여 데이터 없음"을 3회 연속 재확인한다.
     *
     * @author hjKang
     * @since 2026-08-20
     */
    @Test
    void repeatsTheFullJobThreeTimesConsecutivelyWithIdenticalOutcomesAndNoCrossRunLeakage() throws Exception {
        jobLauncherTestUtils.setJob(monthlyValidationJob);

        List<Long> runIds = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            long runNo = ThreadLocalRandom.current().nextLong(1, Integer.MAX_VALUE);
            JobExecution jobExecution = jobLauncherTestUtils.launchJob(jobParameters("req-repeat-" + i, runNo));

            assertThat(jobExecution.getStatus()).isEqualTo(BatchStatus.COMPLETED);
            assertThat(jobExecution.getStepExecutions())
                    .filteredOn(step -> step.getStatus() == BatchStatus.FAILED)
                    .isEmpty();

            Long validationRunId = ValidationRunBatchContext.getValidationRunId(jobExecution.getExecutionContext());
            assertThat(validationRunId).isNotNull();
            // 이전 회차와 같은 JobInstance로 잘못 이어붙지 않고 매번 새 실행이어야 한다.
            assertThat(runIds).doesNotContain(validationRunId);
            runIds.add(validationRunId);
            createdValidationRunIds.add(validationRunId);

            Set<String> completedStepNames = jobExecution.getStepExecutions().stream()
                    .filter(se -> se.getStatus() == BatchStatus.COMPLETED)
                    .map(StepExecution::getStepName)
                    .collect(Collectors.toSet());
            assertThat(completedStepNames).contains(
                    "createRunStep", "selectTargetStep", "regenerateScheduleStep",
                    "capCheckStep", "arbitrageCheckStep", "journalPostingStep",
                    "imbalanceCheckStep", "reconciliationStep", "exceptionGenerationStep");

            ValidationRunRow row = validationRunMapper.findById(validationRunId);
            assertThat(row.getStatus()).isEqualTo("COMPLETED");
            assertThat(row.getRunNo()).isEqualTo(Math.toIntExact(runNo));
        }

        assertThat(runIds).hasSize(3);
        // 3회 각각 자신의 validation_run에만 결과가 귀속되고, 회차 간 산출물이 섞이거나
        // 중복 행이 남지 않는다.
        for (Long runId : runIds) {
            long selectedTargets = jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM fgc.validation_target WHERE validation_run_id = ?",
                    Long.class, runId);
            assertThat(selectedTargets).isZero();
        }
    }
}
