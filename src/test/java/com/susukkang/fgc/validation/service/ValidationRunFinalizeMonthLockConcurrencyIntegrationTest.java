package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.validation.dto.CreateValidationRunCommand;
import com.susukkang.fgc.validation.dto.FinalizeValidationRunResponse;
import com.susukkang.fgc.validation.dto.ValidationRunRow;
import com.susukkang.fgc.validation.repository.ValidationRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 요구사항 : FGC-FUN-044 검증 결과 확정·잠금 (#491 PR 리뷰 반영)
 *
 * 설명 : 확정 조건 7("같은 검증월의 가장 최근 실행인가")의 check-then-act 경합을 실제 PostgreSQL
 * 잠금으로 재현한다. 실행 생성과 확정이 같은 검증월 advisory lock 을 잡는지 검증한다.
 *
 * 타이밍(sleep)으로 "막혔다"를 추정하지 않는다. pg_locks 에서 승인되지 않은 advisory 잠금 대기를
 * 직접 확인한 뒤 잠금을 푼다. 여러 스레드가 각자 커밋해야 하므로 @Transactional 롤백을 쓰지 않고
 * 이 테스트가 만든 실행 ID만 직접 지운다. 같은 검증월의 다른 테스트 데이터는 보존한다. 확정에 성공한 행은 트리거상 지울 수 없으므로
 * 이 테스트는 확정이 거부되는 경로와 생성 경로만 다룬다.
 *
 * 격리 수준 전제: 확정 트랜잭션이 READ COMMITTED(PostgreSQL 기본)라 월 잠금을 얻은 뒤의 체크리스트 조회가
 * 방금 커밋된 새 실행을 본다. REPEATABLE READ 이상으로 바꾸면 스냅샷이 잠금 전에 고정되어 첫 번째 테스트가
 * 실패한다 — 격리 수준을 바꾸는 변경은 이 테스트 통과를 조건으로 한다.
 *
 * @author yslee
 * @since 2026-10-11
 */
@SpringBootTest
@Timeout(60)
class ValidationRunFinalizeMonthLockConcurrencyIntegrationTest {

    private static final LocalDate FINALIZE_MONTH = LocalDate.of(2086, 5, 1);
    private static final LocalDate CREATE_MONTH = LocalDate.of(2086, 6, 1);

    @Autowired
    private ValidationRunFinalizationService finalizationService;
    @Autowired
    private ValidationRunCreateService createService;
    @Autowired
    private ValidationRunRepository validationRunRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final ExecutorService executor = Executors.newFixedThreadPool(2);
    private final Set<Long> ownedRunIds = ConcurrentHashMap.newKeySet();

    @AfterEach
    void cleanUp() throws InterruptedException {
        cleanUp(executor);
    }

    private void cleanUp(ExecutorService workers) throws InterruptedException {
        workers.shutdownNow();
        boolean terminated;
        try {
            terminated = workers.awaitTermination(10, TimeUnit.SECONDS);
        } finally {
            // 종료 대기가 실패해도 이 테스트가 기록한 행의 삭제는 반드시 시도한다.
            for (Long runId : ownedRunIds) {
                jdbcTemplate.update("DELETE FROM fgc.validation_run WHERE validation_run_id = ?", runId);
            }
        }
        assertThat(terminated).isTrue();
    }

    @Test
    void cleanupDeletesOwnedRunsBeforeReportingTerminationTimeout() throws Exception {
        Long runId = insertRun(CREATE_MONTH, "MONTHLY");
        ExecutorService workers = mock(ExecutorService.class);
        when(workers.awaitTermination(10, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> cleanUp(workers)).isInstanceOf(AssertionError.class);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.validation_run WHERE validation_run_id = ?",
                Integer.class, runId)).isZero();
    }

    @Test
    void cleanupDeletesOwnedRunsWhenTerminationWaitIsInterrupted() throws Exception {
        Long runId = insertRun(CREATE_MONTH, "MONTHLY");
        ExecutorService workers = mock(ExecutorService.class);
        when(workers.awaitTermination(10, TimeUnit.SECONDS)).thenThrow(new InterruptedException("종료 대기 중단"));

        assertThatThrownBy(() -> cleanUp(workers)).isInstanceOf(InterruptedException.class);

        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.validation_run WHERE validation_run_id = ?",
                Integer.class, runId)).isZero();
    }

    @Test
    void cleanupPreservesAnotherFixturesRunInTheSameMonth() throws Exception {
        Long otherRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'PRE_CONFIRM') RETURNING validation_run_id
                """, Long.class, FINALIZE_MONTH, ThreadLocalRandom.current().nextInt(1, 1_000_000));
        try {
            Long ownedRunId = insertRun(FINALIZE_MONTH, "PRE_CONFIRM");
            cleanUp();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.validation_run WHERE validation_run_id = ?",
                    Integer.class, ownedRunId)).isZero();
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.validation_run WHERE validation_run_id = ?",
                    Integer.class, otherRunId)).isEqualTo(1);
        } finally {
            jdbcTemplate.update("DELETE FROM fgc.validation_run WHERE validation_run_id = ?", otherRunId);
        }
    }

    // 리뷰 시나리오: 같은 달 새 실행 생성이 진행 중(검증월 잠금 보유, 아직 미커밋)일 때 이전 실행을 확정하면,
    // 확정은 그 커밋을 기다렸다가 새 실행을 보고 조건 7로 거부해야 한다.
    // 잠금이 없으면 확정이 기다리지 않고 "더 최근 실행 0건"으로 보고 FINALIZED 가 된다.
    @Test
    void finalizeWaitsForInFlightRunCreationThenRejectsTheSupersededRun() throws Exception {
        Long olderRunId = createCompletedRun(FINALIZE_MONTH);
        Long userId = systemAdminUserId();
        CountDownLatch monthLocked = new CountDownLatch(1);
        CountDownLatch releaseCreation = new CountDownLatch(1);

        // 실행 생성(attemptCreate)과 같은 순서: 검증월 잠금 → INSERT → 커밋
        Future<?> creation = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            validationRunRepository.lockValidationMonth(FINALIZE_MONTH);
            monthLocked.countDown();
            await(releaseCreation);
            insertRun(FINALIZE_MONTH, "MONTHLY");
        }));
        assertThat(monthLocked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<FinalizeValidationRunResponse> finalize = executor.submit(() -> asSystemAdmin(() ->
                finalizationService.finalizeRun(olderRunId, userId, "FUN044-RACE-" + UUID.randomUUID())));

        awaitAdvisoryLockWaiter(FINALIZE_MONTH);
        assertThat(finalize.isDone()).isFalse();

        releaseCreation.countDown();
        creation.get(10, TimeUnit.SECONDS);

        assertThat(finalizeFailure(finalize)).satisfies(exception -> {
            assertThat(exception.getErrorCode()).isEqualTo(FgcErrorCode.VRUN_002);
            assertThat(exception.getParams()).containsEntry("n", 1L);
        });
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.validation_run WHERE validation_run_id = ?",
                String.class, olderRunId)).isEqualTo("COMPLETED");
    }

    // 반대 방향: 확정이 검증월 잠금을 잡고 있는 동안에는 같은 달 실행 생성이 끼어들지 못하고 기다린다.
    @Test
    void runCreationWaitsWhileTheMonthIsLockedByFinalization() throws Exception {
        CountDownLatch monthLocked = new CountDownLatch(1);
        CountDownLatch releaseFinalization = new CountDownLatch(1);

        Future<?> finalization = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            validationRunRepository.lockValidationMonth(CREATE_MONTH);
            monthLocked.countDown();
            await(releaseFinalization);
        }));
        assertThat(monthLocked.await(10, TimeUnit.SECONDS)).isTrue();

        Future<ValidationRunRow> creation = executor.submit(() -> {
            ValidationRunRow run = createService.create(
                    new CreateValidationRunCommand(CREATE_MONTH, ValidationRunType.MONTHLY, null));
            ownedRunIds.add(run.getValidationRunId());
            return run;
        });

        awaitAdvisoryLockWaiter(CREATE_MONTH);
        assertThat(creation.isDone()).isFalse();

        releaseFinalization.countDown();
        finalization.get(10, TimeUnit.SECONDS);
        assertThat(creation.get(10, TimeUnit.SECONDS).getStatus()).isEqualTo("CREATED");
    }

    // 이 검증월 키를 기다리는 세션만 센다. bigint 키는 pg_locks 에 classid(상위 32비트)·objid(하위 32비트)로
    // 나뉘어 들어가므로 다시 합쳐 ValidationRunRepository#lockValidationMonth 와 같은 식의 해시와 비교한다.
    private void awaitAdvisoryLockWaiter(LocalDate month) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Integer waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                      FROM pg_locks l
                     WHERE l.locktype = 'advisory'
                       AND NOT l.granted
                       AND ((l.classid::bigint << 32) | l.objid::bigint)
                           = hashtextextended(?, 0)
                    """, Integer.class,
                    ValidationRunRepository.VALIDATION_MONTH_LOCK_PREFIX + month);
            if (waiting != null && waiting > 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("10초 안에 검증월 advisory 잠금을 기다리는 세션이 없었다 — 잠금을 잡지 않는 경로다");
    }

    private FgcBusinessException finalizeFailure(Future<FinalizeValidationRunResponse> finalize) throws Exception {
        try {
            FinalizeValidationRunResponse response = finalize.get(10, TimeUnit.SECONDS);
            throw new AssertionError("이전 실행이 확정되면 안 된다: " + response);
        } catch (ExecutionException e) {
            assertThat(e.getCause()).isInstanceOf(FgcBusinessException.class);
            return (FgcBusinessException) e.getCause();
        }
    }

    private <T> T asSystemAdmin(java.util.concurrent.Callable<T> action) throws Exception {
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                "admin", null, List.of(new SimpleGrantedAuthority("ROLE_SYSTEM_ADMIN"))));
        try {
            return action.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) {
                throw new AssertionError("테스트 조정 래치 대기 시간 초과");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private Long insertRun(LocalDate month, String runType) {
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, ?)
                RETURNING validation_run_id
                """, Long.class, month, ThreadLocalRandom.current().nextInt(1, 1_000_000), runType);
        ownedRunIds.add(runId);
        return runId;
    }

    private Long createCompletedRun(LocalDate month) {
        Long runId = insertRun(month, "PRE_CONFIRM");
        jdbcTemplate.update("""
                UPDATE fgc.validation_run
                   SET status = 'RUNNING', current_step = 1, started_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        jdbcTemplate.update("""
                UPDATE fgc.validation_run
                   SET status = 'COMPLETED', current_step = 8, completed_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, runId);
        return runId;
    }

    private Long systemAdminUserId() {
        return jdbcTemplate.queryForObject("""
                SELECT u.user_id
                  FROM fgc.app_user u
                  JOIN fgc.app_role r ON r.role_id = u.role_id
                 WHERE r.role_code = 'SYSTEM_ADMIN'
                 ORDER BY u.user_id LIMIT 1
                """, Long.class);
    }
}
