package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.dto.JournalLineDraft;
import com.susukkang.fgc.journal.dto.ReverseJournalCommand;
import com.susukkang.fgc.journal.repository.JournalHeaderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.postgresql.util.PSQLException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * #375 저장 경계 회귀. 실제 PostgreSQL 제약과 서로 다른 REQUIRES_NEW 트랜잭션을 검증한다.
 * 정상 저장은 실제 커밋하므로 UUID로 소유한 DRAFT 헤더·라인만 종료 시 정리한다.
 * append-only 감사 이력은 삭제하지 않으며 고유 request_id로 식별한다. 격리 테스트 DB에서 실행한다.
 */
@SpringBootTest
class JournalPersistenceBoundaryIntegrationTest {

    @Autowired
    private JournalPersistenceService persistenceService;
    @Autowired
    private JournalCorrectionService correctionService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoSpyBean
    private JournalHeaderRepository headerRepository;
    @MockitoSpyBean
    private ConstraintErrorCodeResolver constraintResolver;

    private final String marker = "it-journal-375-" + UUID.randomUUID();
    // 기존 업무월/다른 테스트의 고정 월과 겹치지 않는 4자리 연도 범위를 쓴다.
    private final LocalDate month = LocalDate.of(
            ThreadLocalRandom.current().nextInt(4000, 7000),
            ThreadLocalRandom.current().nextInt(1, 13), 1);
    private Long actorId;

    @BeforeEach
    void requireAnUnusedNumberingMonth() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.journal_header
                 WHERE journal_no LIKE ? OR journal_no LIKE ?
                """, Long.class, numberPrefix(month) + "%", numberPrefix(month.plusMonths(1)) + "%"))
                .as("테스트가 기존 채번 데이터에 영향을 주지 않는 전용 월")
                .isZero();
        actorId = jdbcTemplate.queryForObject("SELECT MIN(user_id) FROM fgc.app_user", Long.class);
        assertThat(actorId).isNotNull();
    }

    @AfterEach
    void removeOnlyOwnedDrafts() {
        jdbcTemplate.update("""
                DELETE FROM fgc.journal_line WHERE journal_header_id IN (
                    SELECT journal_header_id FROM fgc.journal_header WHERE description = ?
                )
                """, marker);
        jdbcTemplate.update("DELETE FROM fgc.journal_header WHERE description = ?", marker);
        assertThat(ownedHeaderCount()).isZero();
    }

    @Test
    void concurrentSameSourceRevisionReturnsWinnerAfterActualSourceConstraintFailure() throws Exception {
        JournalHeaderDraft draft = draft(month, "same-source");
        CyclicBarrier beforeInsert = new CyclicBarrier(2);
        AtomicInteger allocated = new AtomicInteger();
        List<Long> transactionIds = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            recordTransaction(transactionIds);
            // 번호 충돌이 원천 제약을 가리지 않도록 두 요청에는 서로 다른 번호만 제공한다.
            // DB INSERT, UNIQUE 위반, 실패 트랜잭션 rollback과 승자 재조회는 실제로 수행한다.
            int sequence = allocated.incrementAndGet();
            beforeInsert.await(15, TimeUnit.SECONDS);
            return sequence;
        }).when(headerRepository).findNextJournalSeq(year(month), monthNumber(month));

        List<JournalHeaderRow> results = concurrently(
                () -> persistenceService.saveDraft(draft, actorId, marker + "-first"),
                () -> persistenceService.saveDraft(draft, actorId, marker + "-second"));

        assertThat(results).extracting(JournalHeaderRow::getJournalHeaderId).containsOnly(
                results.getFirst().getJournalHeaderId());
        assertThat(transactionIds).hasSize(2).doesNotHaveDuplicates();
        assertThat(allocated.get()).isEqualTo(2);
        assertOwnedWrites(1, 2, 1);
        // 사전 조회 2회 + 승자의 저장 후 조회 1회 + 실패 시도 밖의 승자 재조회 1회.
        verify(headerRepository, times(4)).findBySourceKey(draft.getJournalType(),
                draft.getSourceEntityType(), draft.getSourceEntityId(), draft.getRevisionNo());
        ArgumentCaptor<Throwable> error = ArgumentCaptor.forClass(Throwable.class);
        verify(constraintResolver).extractConstraintName(error.capture());
        assertUniqueViolation(error.getValue(), "uq_journal_source_revision");
    }

    @Test
    void concurrentDistinctSourcesRetryOnlyTheActualJournalNumberCollision() throws Exception {
        CyclicBarrier afterNumberRead = new CyclicBarrier(2);
        AtomicInteger attempts = new AtomicInteger();
        List<Long> transactionIds = new CopyOnWriteArrayList<>();
        doAnswer(invocation -> {
            recordTransaction(transactionIds);
            int attempt = attempts.incrementAndGet();
            // Spring Data @Query 메서드는 인터페이스 메서드라 spy의 callRealMethod로 실행할 수 없다.
            // 운영 네이티브 쿼리와 같은 식을 현재 REQUIRES_NEW 연결에서 실제로 실행한다.
            Integer sequence = jdbcTemplate.queryForObject("""
                    SELECT COALESCE(MAX(CAST(SUBSTRING(journal_no FROM 'JV-\\d{4}-\\d{2}-(\\d+)$') AS int)), 0) + 1
                      FROM fgc.journal_header
                     WHERE journal_no LIKE 'JV-' || ? || '-' || ? || '-%'
                    """, Integer.class, year(month), monthNumber(month));
            // 처음 두 트랜잭션이 같은 MAX+1을 읽은 뒤 동시에 INSERT하도록 한다.
            // 충돌 후 세 번째 시도는 DB에서 새 번호를 실제로 다시 읽는다.
            if (attempt <= 2) {
                afterNumberRead.await(15, TimeUnit.SECONDS);
            }
            return sequence;
        }).when(headerRepository).findNextJournalSeq(year(month), monthNumber(month));

        List<JournalHeaderRow> results = concurrently(
                () -> persistenceService.saveDraft(draft(month, "source-a"), actorId, marker + "-a"),
                () -> persistenceService.saveDraft(draft(month, "source-b"), actorId, marker + "-b"));

        assertThat(results).extracting(JournalHeaderRow::getJournalHeaderId).doesNotHaveDuplicates();
        assertThat(results).extracting(JournalHeaderRow::getJournalNo).containsExactlyInAnyOrder(
                numberPrefix(month) + "0001", numberPrefix(month) + "0002");
        assertThat(transactionIds).hasSize(3).doesNotHaveDuplicates();
        assertOwnedWrites(2, 4, 2);
        ArgumentCaptor<Throwable> errors = ArgumentCaptor.forClass(Throwable.class);
        verify(constraintResolver, times(2)).extractConstraintName(errors.capture());
        errors.getAllValues().forEach(error -> assertUniqueViolation(error, "uq_journal_no"));
    }

    @Test
    void journalNumberContinuesAtMonthEndAndRestartsOnTheNextMonth() {
        seedHeader(month, 41);
        LocalDate monthEnd = month.plusMonths(1).minusDays(1);

        JournalHeaderRow last = persistenceService.saveDraft(draft(monthEnd, "month-end"), actorId, marker + "-last");
        JournalHeaderRow first = persistenceService.saveDraft(draft(month.plusMonths(1), "next-month"), actorId, marker + "-next");

        assertThat(last.getJournalNo()).isEqualTo(numberPrefix(month) + "0042");
        assertThat(last.getJournalDate()).isEqualTo(monthEnd);
        assertThat(first.getJournalNo()).isEqualTo(numberPrefix(month.plusMonths(1)) + "0001");
        assertThat(first.getJournalDate()).isEqualTo(month.plusMonths(1));
        assertOwnedWrites(3, 4, 2);
    }

    @Test
    void lastMonthlySequenceSucceedsAndOverflowLeavesNoAdditionalWrites() {
        seedHeader(month, 9998);
        JournalHeaderRow last = persistenceService.saveDraft(draft(month, "last-number"), actorId, marker + "-last");

        assertThat(last.getJournalNo()).isEqualTo(numberPrefix(month) + "9999");
        assertThatThrownBy(() -> persistenceService.saveDraft(draft(month, "overflow"), actorId, marker + "-overflow"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("월간 일련번호 상한(9999) 초과")
                .hasMessageContaining("seq=10000");
        assertOwnedWrites(2, 2, 1);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fgc.audit_log WHERE request_id = ?",
                Long.class, marker + "-overflow")).isZero();
    }

    @Test
    void journalNumberCollisionStopsAfterThreeSeparateRolledBackTransactions() {
        seedHeader(month, 1);
        List<Long> transactionIds = new ArrayList<>();
        doAnswer(invocation -> {
            recordTransaction(transactionIds);
            return 1;
        }).when(headerRepository).findNextJournalSeq(year(month), monthNumber(month));

        assertThatThrownBy(() -> persistenceService.saveDraft(draft(month, "exhausted"), actorId, marker + "-exhausted"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertUniqueViolation(error, "uq_journal_no"));

        assertThat(transactionIds).hasSize(3).doesNotHaveDuplicates();
        verify(headerRepository, times(3)).findNextJournalSeq(year(month), monthNumber(month));
        assertOwnedWrites(1, 0, 0);
    }

    @Test
    void unrelatedLineConstraintFailureIsNotRetriedAndRollsBackAllWrites() {
        JournalHeaderDraft valid = draft(month, "invalid-lines");
        JournalHeaderDraft invalid = valid.toBuilder().lines(List.of(
                valid.getLines().getFirst(), valid.getLines().getLast().toBuilder().lineNo(1).build())).build();

        assertThatThrownBy(() -> persistenceService.saveDraft(invalid, actorId, marker + "-line-error"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .satisfies(error -> assertUniqueViolation(error, "uq_journal_line"));

        verify(headerRepository).findNextJournalSeq(year(month), monthNumber(month));
        assertOwnedWrites(0, 0, 0);
        ArgumentCaptor<Throwable> errors = ArgumentCaptor.forClass(Throwable.class);
        verify(constraintResolver, atLeastOnce()).extractConstraintName(errors.capture());
        errors.getAllValues().forEach(error -> assertUniqueViolation(error, "uq_journal_line"));
    }

    @Test
    void sameRevisionOfAReversedJournalReturnsOriginalWithoutAnotherDraftOrAudit() {
        JournalHeaderDraft draft = draft(month, "reversed-source");
        JournalHeaderRow original = persistenceService.saveDraft(draft, actorId, marker + "-original");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            // 실제 정정/상태 전이는 외부 트랜잭션만 rollback하여 append-only 정정그룹도 남기지 않는다.
            status.setRollbackOnly();
            assertThat(headerRepository.markPosted(original.getJournalHeaderId(), actorId)).isEqualTo(1);
            correctionService.reverse(new ReverseJournalCommand(original.getJournalHeaderId(),
                    "#375 REVERSED 원천 멱등 검증", null, actorId));

            JournalHeaderRow repeated = persistenceService.saveDraft(draft, actorId, marker + "-repeated");

            assertThat(repeated.getJournalHeaderId()).isEqualTo(original.getJournalHeaderId());
            assertThat(repeated.getStatus()).isEqualTo("REVERSED");
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM fgc.journal_header
                     WHERE journal_type = ? AND source_entity_type = ? AND source_entity_id = ? AND revision_no = ?
                    """, Long.class, draft.getJournalType().name(), draft.getSourceEntityType(),
                    draft.getSourceEntityId(), draft.getRevisionNo())).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fgc.audit_log WHERE request_id = ?",
                    Long.class, marker + "-repeated")).isZero();
        });

        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fgc.journal_header WHERE journal_header_id = ?",
                String.class, original.getJournalHeaderId())).isEqualTo("DRAFT");
        assertOwnedWrites(1, 2, 1);
    }

    private List<JournalHeaderRow> concurrently(Callable<JournalHeaderRow> first,
                                               Callable<JournalHeaderRow> second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<JournalHeaderRow>> futures = new ArrayList<>();
        try {
            for (Callable<JournalHeaderRow> task : List.of(first, second)) {
                futures.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(15, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("동시 저장 시작 대기 시간 초과");
                    }
                    return task.call();
                }));
            }
            assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            return List.of(futures.getFirst().get(30, TimeUnit.SECONDS), futures.getLast().get(30, TimeUnit.SECONDS));
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(20, TimeUnit.SECONDS))
                    .as("DB lock/statement timeout 이후 모든 작업과 트랜잭션이 종료돼야 정리 가능")
                    .isTrue();
        }
    }

    private void recordTransaction(List<Long> transactionIds) {
        jdbcTemplate.execute("SET LOCAL lock_timeout = '10s'");
        jdbcTemplate.execute("SET LOCAL statement_timeout = '15s'");
        transactionIds.add(jdbcTemplate.queryForObject("SELECT txid_current()", Long.class));
    }

    private JournalHeaderDraft draft(LocalDate date, String sourceSuffix) {
        return JournalHeaderDraft.builder()
                .journalType(JournalType.EXPECTED_INSURER_INCOME)
                .journalDate(date)
                .sourceEntityType("JOURNAL_BOUNDARY_TEST")
                .sourceEntityId(marker + "-" + sourceSuffix)
                .revisionNo(1)
                .description(marker)
                .lines(List.of(
                        JournalLineDraft.builder().lineNo(1).accountCode(JournalAccountCode.EXPECTED_RECEIVABLE)
                                .debitAmount(new BigDecimal("50000.00")).creditAmount(BigDecimal.ZERO).build(),
                        JournalLineDraft.builder().lineNo(2).accountCode(JournalAccountCode.EXPECTED_INCOME)
                                .debitAmount(BigDecimal.ZERO).creditAmount(new BigDecimal("50000.00")).build()))
                .build();
    }

    private void seedHeader(LocalDate date, int sequence) {
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_header (
                    journal_no, journal_date, journal_type, source_entity_type, source_entity_id, revision_no, description
                ) VALUES (?, ?, 'EXPECTED_INSURER_INCOME', 'JOURNAL_BOUNDARY_SEED', ?, 1, ?)
                """, numberPrefix(date) + String.format("%04d", sequence), date, UUID.randomUUID().toString(), marker);
    }

    private void assertOwnedWrites(long headers, long lines, long draftAudits) {
        assertThat(ownedHeaderCount()).isEqualTo(headers);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.journal_line l
                  JOIN fgc.journal_header h USING (journal_header_id)
                 WHERE h.description = ?
                """, Long.class, marker)).isEqualTo(lines);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.audit_log
                 WHERE request_id LIKE ? AND action_code = 'JOURNAL_DRAFT_SAVED'
                """, Long.class, marker + "%")).isEqualTo(draftAudits);
    }

    private Long ownedHeaderCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM fgc.journal_header WHERE description = ?",
                Long.class, marker);
    }

    private void assertUniqueViolation(Throwable throwable, String expectedConstraint) {
        Throwable cause = throwable;
        while (cause != null && !(cause instanceof PSQLException)) {
            cause = cause.getCause();
        }
        assertThat(cause).as("합성 예외가 아닌 실제 PostgreSQL UNIQUE 위반").isInstanceOf(PSQLException.class);
        PSQLException postgres = (PSQLException) cause;
        assertThat(postgres.getSQLState()).isEqualTo("23505");
        assertThat(postgres.getServerErrorMessage()).isNotNull();
        assertThat(postgres.getServerErrorMessage().getConstraint()).isEqualTo(expectedConstraint);
    }

    private String numberPrefix(LocalDate date) {
        return "JV-" + year(date) + "-" + monthNumber(date) + "-";
    }

    private String year(LocalDate date) {
        return String.valueOf(date.getYear());
    }

    private String monthNumber(LocalDate date) {
        return String.format("%02d", date.getMonthValue());
    }
}
