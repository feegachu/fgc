package com.susukkang.fgc.validation.repository;

import com.susukkang.fgc.common.code.ValidationRunStatus;
import com.susukkang.fgc.validation.dto.ValidationRunListRow;
import com.susukkang.fgc.validation.entity.ValidationRun;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ValidationRunMapperIntegrationTest(MyBatis)와 같은 시나리오를 JPA Repository로 검증한다.
 * 특히 각 조건부 @Modifying UPDATE가 실제 DB에서 "조건 불일치 시 0건"으로 동작하는지,
 * clearAutomatically=true 덕분에 같은 트랜잭션에서 뒤이은 findById가 1차 캐시가 아니라
 * DB의 최신 값을 돌려주는지는 Mock으로는 증명되지 않는다.
 */
@SpringBootTest
@Transactional
class ValidationRunRepositoryIntegrationTest {

    @Autowired
    private ValidationRunRepository validationRunRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final ValidationRunStatus CREATED = ValidationRunStatus.CREATED;
    private static final ValidationRunStatus RUNNING = ValidationRunStatus.RUNNING;
    private static final ValidationRunStatus COMPLETED = ValidationRunStatus.COMPLETED;
    private static final ValidationRunStatus FAILED = ValidationRunStatus.FAILED;
    private static final ValidationRunStatus FINALIZED = ValidationRunStatus.FINALIZED;

    private Long insertCreatedRun(LocalDate month, int runNo) {
        return insertCreatedRun(month, runNo, "MONTHLY");
    }

    private Long insertCreatedRun(LocalDate month, int runNo, String runType) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                VALUES (?, ?, ?, 'CREATED')
                RETURNING validation_run_id
                """, Long.class, month, runNo, runType);
    }

    @Test
    void findByIdForUpdateReturnsInsertedRow() {
        Long id = insertCreatedRun(LocalDate.of(2026, 8, 1), 1);

        Optional<ValidationRun> found = validationRunRepository.findByIdForUpdate(id);

        assertThat(found).isPresent();
        assertThat(found.get().getStatus()).isEqualTo(CREATED);
        assertThat(found.get().getValidationMonth()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(found.get().getRunNo()).isEqualTo(1);
    }

    @Test
    void updateStatusIfCurrentSucceedsWhenExpectedStatusMatches() {
        Long id = insertCreatedRun(LocalDate.of(2026, 8, 1), 2);

        int affected = validationRunRepository.updateStatusIfCurrent(id, CREATED, RUNNING);

        assertThat(affected).isEqualTo(1);
        assertThat(validationRunRepository.findById(id).orElseThrow().getStatus()).isEqualTo(RUNNING);
    }

    @Test
    void updateStatusIfCurrentReturnsZeroWhenExpectedStatusIsStale() {
        Long id = insertCreatedRun(LocalDate.of(2026, 8, 1), 3);

        int affected = validationRunRepository.updateStatusIfCurrent(id, RUNNING, COMPLETED);

        assertThat(affected).isZero();
        assertThat(validationRunRepository.findById(id).orElseThrow().getStatus()).isEqualTo(CREATED);
    }

    @Test
    void updateStatusIfCurrentReturnsZeroWhenRunDoesNotExist() {
        int affected = validationRunRepository.updateStatusIfCurrent(999_999_999L, CREATED, RUNNING);

        assertThat(affected).isZero();
    }

    @Test
    void findNextRunNoReturnsOneWhenNoRunsExistForMonth() {
        Integer nextRunNo = validationRunRepository.findNextRunNo(LocalDate.of(2026, 9, 1));

        assertThat(nextRunNo).isEqualTo(1);
    }

    @Test
    void findNextRunNoReturnsMaxPlusOne() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        insertCreatedRun(month, 1, "MANUAL_CONTRACT");
        insertCreatedRun(month, 2, "PRE_CONFIRM");

        Integer nextRunNo = validationRunRepository.findNextRunNo(month);

        assertThat(nextRunNo).isEqualTo(3);
    }

    @Test
    void existsActiveMonthlyRunReturnsTrueForActiveMonthlyRun() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        insertCreatedRun(month, 1);

        boolean exists = validationRunRepository.existsByValidationMonthAndRunTypeAndStatusIn(
                month, "MONTHLY", List.of(CREATED, RUNNING));

        assertThat(exists).isTrue();
    }

    @Test
    void existsActiveMonthlyRunReturnsFalseWhenNoActiveRun() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        Long id = insertCreatedRun(month, 1);
        validationRunRepository.updateStatusIfCurrent(id, CREATED, RUNNING);
        jdbcTemplate.update(
                "UPDATE fgc.validation_run SET status = 'COMPLETED', current_step = 8 WHERE validation_run_id = ?",
                id);

        boolean exists = validationRunRepository.existsByValidationMonthAndRunTypeAndStatusIn(
                month, "MONTHLY", List.of(CREATED, RUNNING));

        assertThat(exists).isFalse();
    }

    @Test
    void existsActiveMonthlyRunIgnoresNonMonthlyRunType() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        insertCreatedRun(month, 1, "MANUAL_CONTRACT");

        boolean exists = validationRunRepository.existsByValidationMonthAndRunTypeAndStatusIn(
                month, "MONTHLY", List.of(CREATED, RUNNING));

        assertThat(exists).isFalse();
    }

    @Test
    void existsActiveManualContractRunIgnoresMonthScope() {
        insertCreatedRun(LocalDate.of(2026, 9, 1), 1, "MANUAL_CONTRACT");

        boolean exists = validationRunRepository.existsByRunTypeAndStatusIn(
                "MANUAL_CONTRACT", List.of(CREATED, RUNNING));

        assertThat(exists).isTrue();
    }

    @Test
    void saveGeneratesIdAndAppliesBuilderDefaults() {
        ValidationRun run = ValidationRun.builder()
                .validationMonth(LocalDate.of(2026, 9, 1))
                .runNo(1)
                .runType("MONTHLY")
                .build();

        ValidationRun saved = validationRunRepository.save(run);

        assertThat(saved.getValidationRunId()).isNotNull();
        assertThat(saved.getStatus()).isEqualTo(CREATED);

        Integer currentStep = jdbcTemplate.queryForObject(
                "SELECT current_step FROM fgc.validation_run WHERE validation_run_id = ?",
                Integer.class, saved.getValidationRunId());
        assertThat(currentStep).isZero();
        String policySnapshot = jdbcTemplate.queryForObject(
                "SELECT policy_snapshot::text FROM fgc.validation_run WHERE validation_run_id = ?",
                String.class, saved.getValidationRunId());
        assertThat(policySnapshot).isEqualTo("{}");
    }

    @Test
    void saveFailsOnDuplicateRunNoInSameMonth() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        insertCreatedRun(month, 1);

        ValidationRun duplicate = ValidationRun.builder()
                .validationMonth(month)
                .runNo(1)
                .runType("MANUAL_CONTRACT")
                .build();

        assertThatThrownBy(() -> {
            validationRunRepository.save(duplicate);
            validationRunRepository.flush();
        }).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void transitionToRunningSetsStatusStepAndStartedAt() {
        Long id = insertCreatedRun(LocalDate.of(2026, 9, 1), 1);

        int affected = validationRunRepository.transitionToRunning(id, CREATED, RUNNING);

        assertThat(affected).isEqualTo(1);
        ValidationRun run = validationRunRepository.findById(id).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RUNNING);
        assertThat(run.getCurrentStep()).isEqualTo(1);
        assertThat(run.getStartedAt()).isNotNull();
    }

    @Test
    void transitionToRunningReturnsZeroWhenNotCreated() {
        Long id = insertCreatedRun(LocalDate.of(2026, 9, 1), 1);
        validationRunRepository.transitionToRunning(id, CREATED, RUNNING);

        int affected = validationRunRepository.transitionToRunning(id, CREATED, RUNNING);

        assertThat(affected).isZero();
    }

    @Test
    void updateCurrentStepAdvancesStepWhileStayingRunning() {
        Long id = insertCreatedRun(LocalDate.of(2026, 9, 1), 1);
        validationRunRepository.transitionToRunning(id, CREATED, RUNNING);

        int affected = validationRunRepository.updateCurrentStep(id, 4, RUNNING);

        assertThat(affected).isEqualTo(1);
        ValidationRun run = validationRunRepository.findById(id).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(RUNNING);
        assertThat(run.getCurrentStep()).isEqualTo(4);
    }

    @Test
    void updateCurrentStepReturnsZeroWhenNotRunning() {
        Long id = insertCreatedRun(LocalDate.of(2026, 9, 1), 1);

        int affected = validationRunRepository.updateCurrentStep(id, 2, RUNNING);

        assertThat(affected).isZero();
    }

    @Test
    void updateCurrentStepReturnsZeroWhenGoingBackward() {
        Long id = insertCreatedRun(LocalDate.of(2026, 9, 1), 1);
        validationRunRepository.transitionToRunning(id, CREATED, RUNNING);
        validationRunRepository.updateCurrentStep(id, 5, RUNNING);

        int affected = validationRunRepository.updateCurrentStep(id, 3, RUNNING);

        assertThat(affected).isZero();
        assertThat(validationRunRepository.findById(id).orElseThrow().getCurrentStep()).isEqualTo(5);
    }

    @Test
    void updateCurrentStepAllowsSettingTheSameStepAgain() {
        Long id = insertCreatedRun(LocalDate.of(2026, 9, 1), 1);
        validationRunRepository.transitionToRunning(id, CREATED, RUNNING);
        validationRunRepository.updateCurrentStep(id, 6, RUNNING);

        int affected = validationRunRepository.updateCurrentStep(id, 6, RUNNING);

        assertThat(affected).isEqualTo(1);
    }

    @Test
    void transitionToCompletedSetsStatusStepAndCompletedAt() {
        Long id = insertCreatedRun(LocalDate.of(2026, 9, 1), 1);
        validationRunRepository.transitionToRunning(id, CREATED, RUNNING);

        int affected = validationRunRepository.transitionToCompleted(id, RUNNING, COMPLETED);

        assertThat(affected).isEqualTo(1);
        ValidationRun run = validationRunRepository.findById(id).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(COMPLETED);
        assertThat(run.getCurrentStep()).isEqualTo(8);
        assertThat(run.getCompletedAt()).isNotNull();
    }

    @Test
    void transitionToFailedRecordsFailedStepAndMessage() {
        Long id = insertCreatedRun(LocalDate.of(2026, 9, 1), 1);
        validationRunRepository.transitionToRunning(id, CREATED, RUNNING);
        validationRunRepository.updateCurrentStep(id, 3, RUNNING);

        int affected = validationRunRepository.transitionToFailed(
                id, 5, "차익거래 검증 중 예외 발생", RUNNING, FAILED);

        assertThat(affected).isEqualTo(1);
        ValidationRun run = validationRunRepository.findById(id).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(FAILED);
        assertThat(run.getCurrentStep()).isEqualTo(5);
        assertThat(run.getFailureMessage()).isEqualTo("차익거래 검증 중 예외 발생");
    }

    @Test
    void finalizeIfCompletedTransitionsCompletedStep8ToFinalized() {
        Long userId = insertAppUser("finalizer-1");
        Long id = insertCreatedRun(LocalDate.of(2026, 9, 1), 1);
        validationRunRepository.transitionToRunning(id, CREATED, RUNNING);
        validationRunRepository.transitionToCompleted(id, RUNNING, COMPLETED);

        int affected = validationRunRepository.finalizeIfCompleted(id, userId, "idem-key-1", COMPLETED, FINALIZED);

        assertThat(affected).isEqualTo(1);
        ValidationRun run = validationRunRepository.findById(id).orElseThrow();
        assertThat(run.getStatus()).isEqualTo(FINALIZED);
        assertThat(run.getCurrentStep()).isEqualTo(10);
        assertThat(run.getFinalizedAt()).isNotNull();
        assertThat(run.getFinalizedBy()).isEqualTo(userId);
        assertThat(run.getFinalizeIdempotencyKey()).isEqualTo("idem-key-1");
    }

    @Test
    void finalizeIfCompletedReturnsZeroWhenNotCompletedOrStepMismatch() {
        Long userId = insertAppUser("finalizer-2");
        Long id = insertCreatedRun(LocalDate.of(2026, 9, 1), 1);
        validationRunRepository.transitionToRunning(id, CREATED, RUNNING);

        int affected = validationRunRepository.finalizeIfCompleted(id, userId, "idem-key-2", COMPLETED, FINALIZED);

        assertThat(affected).isZero();
    }

    @Test
    void findValidationRunIdByFinalizeIdempotencyKeyFindsOwner() {
        Long userId = insertAppUser("finalizer-3");
        Long id = insertCreatedRun(LocalDate.of(2026, 9, 1), 1);
        validationRunRepository.transitionToRunning(id, CREATED, RUNNING);
        validationRunRepository.transitionToCompleted(id, RUNNING, COMPLETED);
        validationRunRepository.finalizeIfCompleted(id, userId, "idem-key-3", COMPLETED, FINALIZED);

        Optional<Long> owner = validationRunRepository.findValidationRunIdByFinalizeIdempotencyKey("idem-key-3");

        assertThat(owner).contains(id);
    }

    @Test
    void findFirstByRunTypeAndCreatedAtBetweenReturnsLatestRunNoWithinWindow() {
        OffsetDateTime dayStart = OffsetDateTime.of(2026, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        OffsetDateTime dayEnd = dayStart.plusDays(1);
        // uq_validation_run_active_manual_contract(V13)는 MANUAL_CONTRACT 활성 실행을
        // 동시에 1건만 허용한다 — 첫 번째를 COMPLETED로 종료한 뒤 두 번째를 넣는다.
        Long id1 = insertCreatedRun(LocalDate.of(2026, 9, 1), 1, "MANUAL_CONTRACT");
        validationRunRepository.transitionToRunning(id1, CREATED, RUNNING);
        validationRunRepository.transitionToCompleted(id1, RUNNING, COMPLETED);
        Long id2 = insertCreatedRun(LocalDate.of(2026, 9, 1), 2, "MANUAL_CONTRACT");
        jdbcTemplate.update(
                "UPDATE fgc.validation_run SET created_at = ? WHERE validation_run_id IN (?, ?)",
                dayStart.plusHours(5), id1, id2);

        Optional<ValidationRun> found = validationRunRepository
                .findFirstByRunTypeAndCreatedAtBetweenOrderByRunNoDesc("MANUAL_CONTRACT", dayStart, dayEnd);

        assertThat(found).isPresent();
        assertThat(found.get().getRunNo()).isEqualTo(2);
    }

    private Long insertAppUser(String loginId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.app_user (login_id, password_hash, user_name, role_id)
                VALUES (?, 'test-only', '확정 테스트 사용자', (SELECT min(role_id) FROM fgc.app_role))
                RETURNING user_id
                """, Long.class, loginId);
    }

    // ── #41 목록 조회(search) — ValidationRunMapperIntegrationTest의 search/count와 같은 시나리오 ──

    @Test
    void searchFiltersByMonth() {
        insertCreatedRun(LocalDate.of(2026, 9, 1), 1);
        insertCreatedRun(LocalDate.of(2026, 10, 1), 1);

        Page<ValidationRunListRow> page = validationRunRepository.search(
                LocalDate.of(2026, 9, 1), null, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getValidationMonth()).isEqualTo(LocalDate.of(2026, 9, 1));
    }

    @Test
    void searchFiltersByStatus() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        Long runningId = insertCreatedRun(month, 1);
        validationRunRepository.updateStatusIfCurrent(runningId, CREATED, RUNNING);
        // uq_validation_run_active_month는 월당 활성(CREATED/RUNNING) MONTHLY 실행을 1건만
        // 허용한다 — 위에서 이미 RUNNING 하나를 썼으니 두 번째는 MANUAL_CONTRACT로 넣는다.
        insertCreatedRun(month, 2, "MANUAL_CONTRACT");

        Page<ValidationRunListRow> page = validationRunRepository.search(
                month, RUNNING, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getValidationRunId()).isEqualTo(runningId);
        assertThat(page.getContent().get(0).getStatus()).isEqualTo("RUNNING");
    }

    @Test
    void searchReturnsEmptyPageWhenNoMatch() {
        LocalDate maxMonth = jdbcTemplate.queryForObject(
                "SELECT COALESCE(MAX(validation_month), DATE '2026-01-01') FROM fgc.validation_run",
                LocalDate.class);
        LocalDate unusedMonth = maxMonth.plusYears(100).withDayOfMonth(1);

        Page<ValidationRunListRow> page = validationRunRepository.search(
                unusedMonth, null, PageRequest.of(0, 20));

        assertThat(page.getContent()).isEmpty();
        assertThat(page.getTotalElements()).isZero();
    }

    @Test
    void searchAndCountReturnAllRowsWhenNoFilterGiven() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        Long firstId = insertCreatedRun(month, 1);
        // uq_validation_run_active_month 때문에 같은 달 두 번째 MONTHLY 활성 실행은 못 넣는다.
        Long secondId = insertCreatedRun(month, 2, "MANUAL_CONTRACT");

        Page<ValidationRunListRow> page = validationRunRepository.search(null, null, PageRequest.of(0, 1000));

        assertThat(page.getContent()).extracting(ValidationRunListRow::getValidationRunId)
                .contains(firstId, secondId);
        assertThat(page.getTotalElements()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void searchRespectsPageableOffsetAndLimit() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        insertCreatedRun(month, 1);
        insertCreatedRun(month, 2, "MANUAL_CONTRACT");
        insertCreatedRun(month, 3, "PRE_CONFIRM");

        Page<ValidationRunListRow> page = validationRunRepository.search(month, null, PageRequest.of(1, 1));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getRunNo()).isEqualTo(2);
    }

    @Test
    void searchJoinsTriggeredAndFinalizedLoginIds() {
        LocalDate month = LocalDate.of(2026, 9, 1);
        Long triggeredByUserId = insertAppUser("triggerer-1");
        Long finalizedByUserId = insertAppUser("finalizer-4");
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status, triggered_by)
                VALUES (?, 1, 'MONTHLY', 'CREATED', ?)
                RETURNING validation_run_id
                """, Long.class, month, triggeredByUserId);
        validationRunRepository.transitionToRunning(id, CREATED, RUNNING);
        validationRunRepository.transitionToCompleted(id, RUNNING, COMPLETED);
        validationRunRepository.finalizeIfCompleted(id, finalizedByUserId, "idem-key-search-1", COMPLETED, FINALIZED);

        Page<ValidationRunListRow> page = validationRunRepository.search(month, FINALIZED, PageRequest.of(0, 20));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getTriggeredBy()).isEqualTo("triggerer-1");
        assertThat(page.getContent().get(0).getFinalizedBy()).isEqualTo("finalizer-4");
    }
}
