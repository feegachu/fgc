package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.audit.service.AuditLogService;
import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionLineRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionRequest;
import com.susukkang.fgc.exceptioncase.service.ExceptionCaseService;
import com.susukkang.fgc.exceptioncase.service.JournalCorrectionExceptionActionService;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionRequest;
import com.susukkang.fgc.journal.dto.JournalCorrectionExceptionResponse;
import com.susukkang.fgc.journal.dto.JournalCorrectionResult;
import com.susukkang.fgc.journal.dto.ReverseJournalCommand;
import com.susukkang.fgc.journal.entity.JournalLine;
import com.susukkang.fgc.journal.repository.JournalLineRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

/**
 * #376 실제 행 잠금 경쟁과 정정·예외 종결의 원자성 검증.
 * 별도 트랜잭션에서 커밋한 UUID fixture를 사용하며 PostgreSQL 업무 트리거를 유지한다.
 * append-only 정정그룹·감사는 삭제하지 않는다. Testcontainers 또는 폐기할 격리 DB에서 실행한다.
 */
@SpringBootTest
@Timeout(60)
class JournalCorrectionConcurrencyIntegrationTest {

    @Autowired
    private JournalCorrectionService correctionService;
    @Autowired
    private JournalCorrectionExceptionService requestService;
    @Autowired
    private JournalCorrectionExceptionActionService correctionActionService;
    @Autowired
    private ExceptionCaseService exceptionCaseService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @Autowired
    private EntityManager entityManager;
    @MockitoSpyBean
    private JournalLineRepository lineRepository;
    @MockitoSpyBean
    private AuditLogService auditLogService;

    private final String marker = "it-correction-376-" + UUID.randomUUID();
    private final LocalDate journalDate = LocalDate.of(
            ThreadLocalRandom.current().nextInt(7100, 8100),
            ThreadLocalRandom.current().nextInt(1, 13), 1);
    private Long actorId;
    private String loginId;
    private Long originalId;

    @BeforeEach
    void commitAnOriginalJournalBeforeStartingIndependentTransactions() {
        actorId = jdbcTemplate.queryForObject("SELECT MIN(user_id) FROM fgc.app_user", Long.class);
        loginId = jdbcTemplate.queryForObject(
                "SELECT login_id FROM fgc.app_user WHERE user_id = ?", String.class, actorId);
        originalId = newTransaction().execute(status -> {
            Long contractId = jdbcTemplate.queryForObject(
                    "SELECT MIN(contract_id) FROM fgc.insurance_contract", Long.class);
            Long id = jdbcTemplate.queryForObject("""
                    INSERT INTO fgc.journal_header
                        (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                         revision_no, contract_id, description, created_by)
                    VALUES (?, ?, 'EXPECTED_INSURER_INCOME', 'SCHEDULE_LINE', ?, 1, ?, ?, ?)
                    RETURNING journal_header_id
                    """, Long.class, "TEST-376-" + UUID.randomUUID(), journalDate,
                    marker, contractId, marker, actorId);
            jdbcTemplate.update("""
                    INSERT INTO fgc.journal_line
                        (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount,
                         contract_id, payment_stage)
                    VALUES (?, 1, ?, 650000, 0, ?, 'INSURER_TO_GA'),
                           (?, 2, ?, 0, 650000, ?, 'INSURER_TO_GA')
                    """, id, accountId(JournalAccountCode.EXPECTED_RECEIVABLE), contractId,
                    id, accountId(JournalAccountCode.EXPECTED_INCOME), contractId);
            assertThat(jdbcTemplate.update("""
                    UPDATE fgc.journal_header SET status = 'POSTED', posted_by = ?
                     WHERE journal_header_id = ?
                    """, actorId, id)).isEqualTo(1);
            return id;
        });
        RequestIdContext.set(marker);
    }

    @AfterEach
    void clearRequestContext() {
        RequestIdContext.clear();
    }

    @Test
    void concurrentReversalsLockTheOriginalAndOnlyOneCommits() throws Exception {
        ReverseJournalCommand command = new ReverseJournalCommand(
                originalId, marker, "DOC-376", actorId);

        List<Outcome<JournalCorrectionResult>> outcomes = concurrentlyBehindRowLock(
                LockTarget.JOURNAL, originalId,
                () -> correctionService.reverse(command),
                () -> correctionService.reverse(command));

        assertThat(outcomes.stream().filter(outcome -> outcome.failure() == null)).hasSize(1);
        List<Throwable> failures = outcomes.stream().map(Outcome::failure)
                .filter(java.util.Objects::nonNull).toList();
        assertThat(failures).singleElement().satisfies(failure -> {
            assertThat(failure).isInstanceOf(FgcBusinessException.class);
            assertThat(((FgcBusinessException) failure).getErrorCode()).isEqualTo(FgcErrorCode.LEDG_002);
        });
        assertThat(originalStatus()).isEqualTo("REVERSED");
        assertThat(count("SELECT COUNT(*) FROM fgc.journal_header WHERE reversal_of_id = ?", originalId))
                .isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM fgc.journal_correction_group WHERE original_journal_header_id = ?
                """, originalId)).isEqualTo(1);
        assertThat(count("""
                SELECT COUNT(*) FROM fgc.journal_line l JOIN fgc.journal_header h USING (journal_header_id)
                 WHERE h.reversal_of_id = ?
                """, originalId)).isEqualTo(2);
        assertThat(count("""
                SELECT COUNT(*) FROM fgc.audit_log WHERE entity_type = 'JOURNAL_HEADER'
                   AND entity_id = ? AND action_code = 'JOURNAL_REVERSED'
                """, originalId.toString())).isEqualTo(1);
    }

    @Test
    void concurrentRequestsCreateOnlyOneActiveCaseInitialActionAndAudit() throws Exception {
        List<Outcome<JournalCorrectionExceptionResponse>> outcomes = concurrentlyBehindRowLock(
                LockTarget.JOURNAL, originalId, this::createRequest, this::createRequest);

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        List<JournalCorrectionExceptionResponse> responses = outcomes.stream().map(Outcome::value).toList();
        Long caseId = responses.getFirst().exceptionCaseId();
        assertThat(responses).extracting(JournalCorrectionExceptionResponse::exceptionCaseId)
                .containsOnly(caseId);
        assertThat(responses).extracting(JournalCorrectionExceptionResponse::created)
                .containsExactlyInAnyOrder(true, false);
        assertThat(count("""
                SELECT COUNT(*) FROM fgc.exception_case
                 WHERE source_entity_type = 'JOURNAL_HEADER' AND source_entity_id = ?
                   AND exception_type = 'JOURNAL_CORRECTION_REQUIRED' AND status IN ('NEW', 'IN_REVIEW')
                """, originalId.toString())).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList("""
                SELECT action_seq, action_type, from_status, to_status FROM fgc.exception_action
                 WHERE exception_case_id = ? ORDER BY action_seq
                """, caseId)).containsExactly(Map.of(
                "action_seq", 1, "action_type", "COMMENT", "from_status", "NEW", "to_status", "NEW"));
        assertThat(count("""
                SELECT COUNT(*) FROM fgc.audit_log WHERE entity_type = 'EXCEPTION_CASE'
                   AND entity_id = ? AND action_code = 'JOURNAL_CORRECTION_REQUESTED'
                """, caseId.toString())).isEqualTo(1);
        assertThat(originalStatus()).isEqualTo("POSTED");
    }

    @Test
    void concurrentAssignmentsSerializeAndAllocateConsecutiveHistoryNumbers() throws Exception {
        Long caseId = createRequest().exceptionCaseId();
        ExceptionActionRequest assign = new ExceptionActionRequest(ExceptionActionType.ASSIGN, marker, "DOC-376");
        List<Outcome<ExceptionActionResponse>> outcomes = concurrentlyBehindRowLock(
                LockTarget.EXCEPTION, caseId,
                () -> exceptionCaseService.action(caseId, assign, actorId, loginId),
                () -> exceptionCaseService.action(caseId, assign, actorId, loginId));

        assertThat(outcomes).allSatisfy(outcome -> assertThat(outcome.failure()).isNull());
        assertThat(outcomes.stream().map(outcome -> outcome.value().actionSeq()))
                .containsExactlyInAnyOrder(2, 3);
        assertThat(jdbcTemplate.queryForList("""
                SELECT action_seq FROM fgc.exception_action WHERE exception_case_id = ? ORDER BY action_seq
                """, Integer.class, caseId)).containsExactly(1, 2, 3);
        assertThat(count("""
                SELECT COUNT(*) FROM fgc.exception_action WHERE exception_case_id = ? AND action_type = 'ASSIGN'
                """, caseId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForMap("""
                SELECT status, assigned_to FROM fgc.exception_case WHERE exception_case_id = ?
                """, caseId)).containsAllEntriesOf(Map.of("status", "NEW", "assigned_to", actorId));
        assertThat(count("""
                SELECT COUNT(*) FROM fgc.audit_log WHERE entity_type = 'EXCEPTION_CASE'
                   AND entity_id = ? AND action_code = 'EXCEPTION_ACTION'
                """, caseId.toString())).isEqualTo(2);
    }

    @Test
    void repostLineDatabaseFailureRestoresEveryTableAfterTheServiceTransactionRollsBack() {
        Long caseId = createCaseInReview();
        Snapshot before = snapshot(caseId);
        AtomicBoolean reachedSecondRepostLine = new AtomicBoolean();
        String failingMemo = marker + "-repost-credit";
        doAnswer(invocation -> {
            JournalLine line = invocation.getArgument(0);
            reachedSecondRepostLine.set(true);
            assertThat(originalStatus()).isEqualTo("REVERSED");
            assertThat(count("""
                    SELECT COUNT(*) FROM fgc.journal_header
                     WHERE journal_header_id = ? OR reversal_of_id = ? OR source_entity_id = ?
                    """, originalId, originalId, marker)).isEqualTo(3);
            assertThat(count("SELECT COUNT(*) FROM fgc.journal_line WHERE journal_header_id = ?",
                    line.getJournalHeaderId())).isEqualTo(1);
            // 입력 검증을 통과하고 재기표 헤더·첫 라인까지 저장한 뒤, DB의 실제 UNIQUE 제약을 위반한다.
            // 나머지 저장은 원래 Repository를 사용하며 트리거와 DB 제약은 변경하지 않는다.
            ReflectionTestUtils.setField(line, "lineNo", 1);
            entityManager.persist(line);
            entityManager.flush();
            return line;
        }).when(lineRepository).save(argThat(line -> line != null && failingMemo.equals(line.getMemo())));

        assertThatThrownBy(() -> correctionActionService.correct(caseId, correctionRequest(), actorId, loginId))
                .satisfies(failure -> {
                    PSQLException postgres = postgresFailure(failure);
                    assertThat(postgres.getSQLState()).isEqualTo("23505");
                    assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo("uq_journal_line");
                });

        assertThat(reachedSecondRepostLine).isTrue();
        assertUnchangedInAnotherTransaction(before, caseId);
    }

    @Test
    void finalExceptionAuditDatabaseFailureAlsoRollsBackSuccessfulJournalCorrection() {
        Long caseId = createCaseInReview();
        Snapshot before = snapshot(caseId);
        // 정정 감사는 AuditLogService가 request_id를 80자로 clamp하므로 먼저 성공한다.
        // 마지막 EXCEPTION_ACTION 감사의 실제 varchar(80) 제약 실패로 전체 업무 롤백을 유도한다.
        RequestIdContext.set("a".repeat(81));

        assertThatThrownBy(() -> correctionActionService.correct(caseId, correctionRequest(), actorId, loginId))
                .isInstanceOf(DataAccessException.class)
                .satisfies(failure -> assertThat(postgresFailure(failure).getSQLState()).isEqualTo("22001"));

        verify(auditLogService).record(argThat(event ->
                "JOURNAL_REVERSED_AND_REPOSTED".equals(event.actionCode())
                        && originalId.toString().equals(event.entityId())));
        assertUnchangedInAnotherTransaction(before, caseId);
    }

    /**
     * gate 행 잠금을 획득한 후 두 worker의 실제 PostgreSQL 잠금 대기를 확인한다.
     * 시간 지연으로 경쟁을 추측하지 않으며 서비스가 잠금을 제거하면 관측 단계에서 실패한다.
     */
    private <T> List<Outcome<T>> concurrentlyBehindRowLock(
            LockTarget target, Long targetId, Supplier<T> first, Supplier<T> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<Outcome<T>>> futures = new ArrayList<>();
        List<Integer> workerPids = new CopyOnWriteArrayList<>();
        List<Long> workerTransactions = new CopyOnWriteArrayList<>();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            newTransaction().executeWithoutResult(gate -> {
                jdbcTemplate.queryForObject(target.lockSql, Long.class, targetId);
                for (Supplier<T> task : List.of(first, second)) {
                    futures.add(executor.submit(() -> {
                        RequestIdContext.set(marker + "-" + Thread.currentThread().threadId());
                        try {
                            T value = newTransaction().execute(status -> {
                                jdbcTemplate.execute("SET LOCAL lock_timeout = '20s'");
                                jdbcTemplate.execute("SET LOCAL statement_timeout = '25s'");
                                workerPids.add(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
                                workerTransactions.add(jdbcTemplate.queryForObject("SELECT txid_current()", Long.class));
                                ready.countDown();
                                await(start);
                                return task.get();
                            });
                            return new Outcome<>(value, null);
                        } catch (Throwable failure) {
                            return new Outcome<T>(null, failure);
                        } finally {
                            RequestIdContext.clear();
                        }
                    }));
                }
                await(ready);
                start.countDown();
                assertThat(workerPids).hasSize(2).doesNotHaveDuplicates();
                assertThat(workerTransactions).hasSize(2).doesNotHaveDuplicates();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline) {
                    Long blocked = jdbcTemplate.queryForObject("""
                            SELECT COUNT(DISTINCT pid) FROM pg_locks
                             WHERE pid IN (?, ?) AND NOT granted
                               AND cardinality(pg_blocking_pids(pid)) > 0
                            """, Long.class, workerPids.getFirst(), workerPids.getLast());
                    if (Long.valueOf(2).equals(blocked)) {
                        return;
                    }
                    try {
                        Thread.sleep(20);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("잠금 관측이 중단됐습니다.", interrupted);
                    }
                }
                throw new AssertionError("두 서비스 트랜잭션의 실제 PostgreSQL 잠금 대기를 관측하지 못했습니다.");
            }); // gate COMMIT 후 대기 중인 서비스들이 원본/예외를 차례대로 잠근다.
            return List.of(futures.getFirst().get(30, TimeUnit.SECONDS), futures.getLast().get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(30, TimeUnit.SECONDS))
                    .as("DB timeout 뒤 모든 worker 트랜잭션과 연결이 종료돼야 한다").isTrue();
        }
    }

    private void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("동시 실행 준비 시간 초과");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 실행 대기가 중단됐습니다.", interrupted);
        }
    }

    private JournalCorrectionExceptionResponse createRequest() {
        return requestService.createOrGet(originalId,
                new JournalCorrectionExceptionRequest(marker, "DOC-376"), actorId);
    }

    private Long createCaseInReview() {
        Long caseId = createRequest().exceptionCaseId();
        exceptionCaseService.action(caseId, new ExceptionActionRequest(
                ExceptionActionType.START_REVIEW, marker, "DOC-376"), actorId, loginId);
        return caseId;
    }

    private JournalCorrectionActionRequest correctionRequest() {
        return new JournalCorrectionActionRequest(marker, "DOC-376", journalDate, marker + "-corrected",
                List.of(new JournalCorrectionActionLineRequest(1, JournalAccountCode.EXPECTED_RECEIVABLE.name(),
                                new BigDecimal("620000"), BigDecimal.ZERO, marker + "-repost-debit"),
                        new JournalCorrectionActionLineRequest(2, JournalAccountCode.EXPECTED_INCOME.name(),
                                BigDecimal.ZERO, new BigDecimal("620000"), marker + "-repost-credit")));
    }

    private Snapshot snapshot(Long caseId) {
        TransactionTemplate read = newTransaction();
        read.setReadOnly(true);
        return read.execute(status -> new Snapshot(
                jdbcTemplate.queryForObject("SELECT txid_current()", Long.class),
                jdbcTemplate.queryForList("""
                        SELECT to_jsonb(h)::text FROM fgc.journal_header h
                         WHERE journal_header_id = ? OR reversal_of_id = ? OR source_entity_id = ?
                         ORDER BY journal_header_id
                        """, String.class, originalId, originalId, marker),
                jdbcTemplate.queryForList("""
                        SELECT to_jsonb(l)::text FROM fgc.journal_line l
                          JOIN fgc.journal_header h USING (journal_header_id)
                         WHERE h.journal_header_id = ? OR h.reversal_of_id = ? OR h.source_entity_id = ?
                         ORDER BY journal_line_id
                        """, String.class, originalId, originalId, marker),
                jdbcTemplate.queryForList("""
                        SELECT to_jsonb(g)::text FROM fgc.journal_correction_group g
                         WHERE original_journal_header_id = ? ORDER BY correction_group_key
                        """, String.class, originalId),
                jdbcTemplate.queryForList("""
                        SELECT to_jsonb(e)::text FROM fgc.exception_case e WHERE exception_case_id = ?
                        """, String.class, caseId),
                jdbcTemplate.queryForList("""
                        SELECT to_jsonb(a)::text FROM fgc.exception_action a
                         WHERE exception_case_id = ? ORDER BY action_seq
                        """, String.class, caseId),
                jdbcTemplate.queryForList("""
                        SELECT to_jsonb(a)::text FROM fgc.audit_log a
                         WHERE (entity_type = 'JOURNAL_HEADER' AND entity_id = ?)
                            OR (entity_type = 'EXCEPTION_CASE' AND entity_id = ?)
                         ORDER BY audit_log_id
                        """, String.class, originalId.toString(), caseId.toString())));
    }

    private void assertUnchangedInAnotherTransaction(Snapshot before, Long caseId) {
        Snapshot after = snapshot(caseId);
        assertThat(after.transactionId()).isNotEqualTo(before.transactionId());
        assertThat(after).usingRecursiveComparison().ignoringFields("transactionId").isEqualTo(before);
        assertThat(after.headers()).hasSize(1);
        assertThat(after.lines()).hasSize(2);
        assertThat(after.groups()).isEmpty();
        assertThat(after.cases()).hasSize(1);
        assertThat(after.actions()).hasSize(2); // 초기 COMMENT + START_REVIEW만 남음
        assertThat(after.audits()).hasSize(2); // 정정 요청 + 검토 시작 감사만 남음
        assertThat(originalStatus()).isEqualTo("POSTED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fgc.exception_case WHERE exception_case_id = ?",
                String.class, caseId)).isEqualTo("IN_REVIEW");
    }

    private TransactionTemplate newTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private Long accountId(JournalAccountCode code) {
        return jdbcTemplate.queryForObject("SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?",
                Long.class, code.name());
    }

    private String originalStatus() {
        return jdbcTemplate.queryForObject("SELECT status FROM fgc.journal_header WHERE journal_header_id = ?",
                String.class, originalId);
    }

    private Long count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private PSQLException postgresFailure(Throwable failure) {
        Throwable cause = failure;
        while (cause != null && !(cause instanceof PSQLException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("합성 오류가 아닌 실제 PostgreSQL 제약 실패").isInstanceOf(PSQLException.class);
        return (PSQLException) cause;
    }

    private enum LockTarget {
        JOURNAL("SELECT journal_header_id FROM fgc.journal_header WHERE journal_header_id = ? FOR UPDATE"),
        EXCEPTION("SELECT exception_case_id FROM fgc.exception_case WHERE exception_case_id = ? FOR UPDATE");

        private final String lockSql;

        LockTarget(String lockSql) {
            this.lockSql = lockSql;
        }
    }

    private record Outcome<T>(T value, Throwable failure) { }

    private record Snapshot(Long transactionId, List<String> headers, List<String> lines,
                            List<String> groups, List<String> cases, List<String> actions, List<String> audits) { }
}
