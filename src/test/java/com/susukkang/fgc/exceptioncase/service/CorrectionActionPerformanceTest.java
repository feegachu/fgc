package com.susukkang.fgc.exceptioncase.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
import com.susukkang.fgc.common.code.ExceptionActionType;
import com.susukkang.fgc.common.web.RequestIdContext;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.ExceptionActionResponse;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionLineRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionRequest;
import com.susukkang.fgc.exceptioncase.dto.JournalCorrectionActionResponse;
import com.susukkang.fgc.journal.service.JournalCorrectionService;
import jakarta.persistence.EntityManager;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.ToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #376 잔여 예외 조치 전환 전후 비교: baseline=bc35de82, 원장 정정 자체는 양쪽 모두 기존 JPA.
 * 10 warmups + 30 alternating samples, 서비스 및 실제 commit 포함, 픽스처/검증/HTTP 제외.
 * 정정 요청 생성·최초 COMMENT 저장은 픽스처 범위이며 이번 수치의 측정 대상이 아니다.
 * SQL은 JDBC execute 호출 수이며 트리거 내부 SQL은 제외한다. 선택한 상태·이력·감사·분개 필드와 null을 비교한다.
 * baseline은 업무 알고리즘·SQL 복원본의 직접 호출이므로 구버전 서비스 프록시 비용까지 재현하지 않는다.
 * 잠금 비교는 동일 행을 다른 연결이 먼저 잠그고 pg_blocking_pids+pg_locks로 실제 대기를 확인한다.
 * 확인 후 50ms 더 보유한 시간은 별도 기록하며 총 소요/잠금 SQL 시간에서 숨기거나 성능 개선으로 해석하지 않는다.
 * commit된 감사 로그를 삭제하지 않으므로 loopback의 전용 폐기 DB만 허용한다.
 */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.jpa.properties.hibernate.cache.use_query_cache=false",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
@Import(CorrectionActionPerformanceTest.ObservationConfig.class)
@EnabledIfSystemProperty(named = "fgc.correction.action.performance", matches = "true")
class CorrectionActionPerformanceTest {
    private static final Logger log = LoggerFactory.getLogger(CorrectionActionPerformanceTest.class);
    private static final int WARMUPS = 10;
    private static final int SAMPLES = 30;
    private static final int CONTROLLED_HOLD_MS = 50;
    private static final LocalDate DATE = LocalDate.of(2095, 7, 15);
    private static final ThreadLocal<Observation> OBSERVATION = new ThreadLocal<>();
    private final String marker = "CAPERF-" + UUID.randomUUID();

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ExceptionCaseService actions;
    @Autowired private JournalCorrectionExceptionActionService correctionActions;
    @Autowired private JournalCorrectionService corrections;
    @Autowired private AuditLogRepository audits;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManager entityManager;
    private TransactionTemplate transaction;
    private CorrectionActionBaseline baseline;
    private Long actor;
    private String login;
    private Long contract;
    private Long debitAccount;
    private Long creditAccount;

    @DynamicPropertySource
    static void requireDisposableDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/fgc_correction_perf_[a-z0-9_]+\\?currentSchema=fgc")) {
            throw new IllegalStateException("정정 조치 성능 비교에는 loopback의 fgc_correction_perf_* 전용 DB가 필요합니다.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void compareCommittedActionsCorrectionAndObservedRowLockWait() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class)).startsWith("fgc_correction_perf_");
        transaction = new TransactionTemplate(transactionManager);
        baseline = new CorrectionActionBaseline(baselineSession(), audits, objectMapper, entityManager, corrections);
        actor = jdbc.queryForObject("SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
        login = jdbc.queryForObject("SELECT login_id FROM fgc.app_user WHERE user_id = ?", String.class, actor);
        contract = jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
        debitAccount = jdbc.queryForObject("SELECT journal_account_id FROM fgc.journal_account WHERE account_code = 'EXPECTED_RECEIVABLE'", Long.class);
        creditAccount = jdbc.queryForObject("SELECT journal_account_id FROM fgc.journal_account WHERE account_code = 'EXPECTED_INCOME'", Long.class);
        log.info("CORRECTION_ACTION_PERF_SCOPE baseline=bc35de82 journal_correction=shared_current_JPA warmups={} samples={} commit_included=true trigger_internal_sql_included=false marker={} cleanup=drop_disposable_database", WARMUPS, SAMPLES, marker);
        long actionsBefore = count("SELECT count(*) FROM fgc.exception_action");
        long auditsBefore = count("SELECT count(*) FROM fgc.audit_log");
        long groupsBefore = count("SELECT count(*) FROM fgc.journal_correction_group");
        compare(Scenario.ASSIGN, false);
        compare(Scenario.START_REVIEW, false);
        compare(Scenario.CORRECT, false);
        compare(Scenario.ASSIGN, true);
        long executions = 2L * (WARMUPS + SAMPLES);
        assertThat(count("SELECT count(*) FROM fgc.exception_action") - actionsBefore).isEqualTo(executions * 4);
        assertThat(count("SELECT count(*) FROM fgc.audit_log") - auditsBefore).isEqualTo(executions * 5);
        assertThat(count("SELECT count(*) FROM fgc.journal_correction_group") - groupsBefore).isEqualTo(executions);
        log.info("CORRECTION_ACTION_PERF_ROWS cases={} actions={} audits={} correction_groups={} equivalence_verified=true", executions * 4, executions * 4, executions * 5, executions);
    }

    private void compare(Scenario scenario, boolean contended) throws Exception {
        List<Sample> oldSamples = new ArrayList<>();
        List<Sample> newSamples = new ArrayList<>();
        for (int i = -WARMUPS; i < SAMPLES; i++) {
            Fixture oldFixture = fixture(scenario);
            Fixture newFixture = fixture(scenario);
            Sample oldSample;
            Sample newSample;
            if (i % 2 == 0) {
                oldSample = execute(oldFixture, scenario, true, contended);
                newSample = execute(newFixture, scenario, false, contended);
            } else {
                newSample = execute(newFixture, scenario, false, contended);
                oldSample = execute(oldFixture, scenario, true, contended);
            }
            assertThat(snapshot(newFixture, scenario, newSample.response()))
                    .as("%s equivalent committed values iteration %s", scenario, i)
                    .isEqualTo(snapshot(oldFixture, scenario, oldSample.response()));
            if (i >= 0) { oldSamples.add(oldSample); newSamples.add(newSample); }
        }
        report(scenario.name().toLowerCase(Locale.ROOT) + (contended ? "-row-lock-contention" : ""), oldSamples, newSamples);
    }

    private Sample execute(Fixture fixture, Scenario scenario, boolean old, boolean contended) throws Exception {
        if (!contended) return measure(fixture, scenario, old);
        // 독립 연결이 잠금을 보유하고 서비스 SQL이 실제로 대기했는지 PostgreSQL에서 확인한다.
        try (Connection holder = dataSource.getConnection(); var worker = Executors.newSingleThreadExecutor()) {
            holder.setAutoCommit(false);
            int holderPid;
            try (Statement statement = holder.createStatement(); var rs = statement.executeQuery("SELECT pg_backend_pid()")) {
                rs.next(); holderPid = rs.getInt(1);
            }
            try (PreparedStatement statement = holder.prepareStatement("SELECT exception_case_id FROM fgc.exception_case WHERE exception_case_id = ? FOR UPDATE")) {
                statement.setLong(1, fixture.caseId());
                try (var rs = statement.executeQuery()) { assertThat(rs.next()).isTrue(); }
            }
            var pending = worker.submit(() -> measure(fixture, scenario, old));
            long confirmedAt = 0;
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
                while (System.nanoTime() < deadline) {
                    boolean blocked = Boolean.TRUE.equals(jdbc.queryForObject("""
                            SELECT EXISTS (
                              SELECT 1 FROM pg_stat_activity a
                               WHERE a.datname = current_database() AND ? = ANY(pg_blocking_pids(a.pid))
                                 AND a.wait_event_type = 'Lock'
                                 AND EXISTS (SELECT 1 FROM pg_locks l WHERE l.pid = a.pid AND NOT l.granted)
                            )
                            """, Boolean.class, holderPid));
                    if (blocked) { confirmedAt = System.nanoTime(); break; }
                    if (pending.isDone()) break;
                    Thread.sleep(2);
                }
                assertThat(confirmedAt).as("actual pg_blocking_pids / ungranted pg_locks wait").isPositive();
                Thread.sleep(CONTROLLED_HOLD_MS);
            } finally {
                // 실패 시에도 먼저 잠금을 해제해야 worker 종료 대기가 교착되지 않는다.
                holder.rollback();
            }
            long controlledHold = System.nanoTime() - confirmedAt;
            Sample sample = pending.get(15, TimeUnit.SECONDS);
            assertThat(sample.lockSqlNanos()).isPositive();
            return new Sample(sample.nanos(), sample.response(), sample.sql(), sample.commits(), sample.sqlNanos(),
                    sample.lockSqlNanos(), controlledHold);
        }
    }

    private Sample measure(Fixture fixture, Scenario scenario, boolean old) {
        Observation observation = new Observation();
        RequestIdContext.set(marker);
        OBSERVATION.set(observation);
        long started = System.nanoTime();
        try {
            // 양쪽 동일한 외부 REQUIRED 트랜잭션 시작/commit. 실제 JPA 서비스도 이 트랜잭션에 참여한다.
            Object response = transaction.execute(status -> {
                if (scenario == Scenario.CORRECT) {
                    var request = correctionRequest();
                    return old ? baseline.correct(fixture.caseId(), request, actor, login)
                            : correctionActions.correct(fixture.caseId(), request, actor, login);
                }
                var request = new ExceptionActionRequest(ExceptionActionType.valueOf(scenario.name()), "성능 비교 조치", null);
                return old ? baseline.action(fixture.caseId(), request, actor, login, false)
                        : actions.action(fixture.caseId(), request, actor, login);
            });
            return new Sample(System.nanoTime() - started, response, List.copyOf(observation.sql),
                    observation.commits, observation.sqlNanos, observation.lockSqlNanos, 0);
        } finally {
            OBSERVATION.remove();
            RequestIdContext.clear();
        }
    }

    private Fixture fixture(Scenario scenario) {
        return transaction.execute(status -> {
            String source = UUID.randomUUID().toString();
            Long original = null;
            if (scenario == Scenario.CORRECT) {
                original = jdbc.queryForObject("""
                        INSERT INTO fgc.journal_header
                            (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                             revision_no, contract_id, description, created_by)
                        VALUES (?, ?, 'EXPECTED_INSURER_INCOME', 'SCHEDULE_LINE', ?, 1, ?, ?, ?)
                        RETURNING journal_header_id
                        """, Long.class, "TEST-CAPERF-" + source, DATE, source, contract, marker, actor);
                jdbc.update("""
                        INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id,
                            debit_amount, credit_amount, contract_id, payment_stage, memo)
                        VALUES (?, 1, ?, 650000, 0, ?, 'INSURER_TO_GA', NULL),
                               (?, 2, ?, 0, 650000, ?, 'INSURER_TO_GA', NULL)
                        """, original, debitAccount, contract, original, creditAccount, contract);
                jdbc.update("UPDATE fgc.journal_header SET status='POSTED', posted_by=? WHERE journal_header_id=?", actor, original);
            }
            Long id = jdbc.queryForObject("""
                    INSERT INTO fgc.exception_case
                        (exception_key, exception_type, severity, status, source_entity_type, source_entity_id, title)
                    VALUES (?, ?, 'WARNING', ?, ?, ?, ?) RETURNING exception_case_id
                    """, Long.class, "CAPERF:" + source, original == null ? "DATA_QUALITY" : "JOURNAL_CORRECTION_REQUIRED",
                    original == null ? "NEW" : "IN_REVIEW", original == null ? "IT" : "JOURNAL_HEADER",
                    original == null ? source : original.toString(), marker);
            return new Fixture(id, original);
        });
    }

    private JournalCorrectionActionRequest correctionRequest() {
        return new JournalCorrectionActionRequest("원장 금액 정정", "DOC-PERF", DATE, "정정 성능 비교", List.of(
                new JournalCorrectionActionLineRequest(1, "EXPECTED_RECEIVABLE", new BigDecimal("620000"), BigDecimal.ZERO, null),
                new JournalCorrectionActionLineRequest(2, "EXPECTED_INCOME", BigDecimal.ZERO, new BigDecimal("620000"), null)));
    }

    private Map<String, Object> snapshot(Fixture fixture, Scenario scenario, Object response) {
        String expectedStatus = switch (scenario) { case ASSIGN -> "NEW"; case START_REVIEW -> "IN_REVIEW"; case CORRECT -> "RESOLVED"; };
        if (response instanceof ExceptionActionResponse action) {
            assertThat(action.exceptionActionId()).isNull();
            assertThat(action.actionSeq()).isEqualTo(1);
            assertThat(action.fromStatus().name()).isEqualTo("NEW");
            assertThat(action.toStatus().name()).isEqualTo(expectedStatus);
            assertThat(action.actionType()).isEqualTo(scenario.name());
            assertThat(action.reason()).isEqualTo("성능 비교 조치");
            assertThat(action.evidenceRef()).isNull();
            assertThat(action.actionBy()).isEqualTo(actor);
            assertThat(action.actionByLoginId()).isEqualTo(login);
            assertThat(action.actionAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        }
        Map<String, Object> state = jdbc.queryForMap("""
                SELECT status, assigned_to, resolved_at IS NOT NULL AS resolved, reason_code,
                       validation_run_id, policy_version_id, created_at IS NOT NULL AS created
                  FROM fgc.exception_case WHERE exception_case_id = ?
                """, fixture.caseId());
        assertThat(state.get("status")).isEqualTo(expectedStatus);
        assertThat(state.get("assigned_to")).isEqualTo(scenario == Scenario.ASSIGN ? actor : null);
        assertThat(state.get("resolved")).isEqualTo(scenario == Scenario.CORRECT);
        assertThat(state.get("created")).isEqualTo(true);
        var history = jdbc.queryForList("""
                SELECT action_seq, from_status, to_status, action_type, reason, evidence_ref, action_by,
                       action_at IS NOT NULL AS acted
                  FROM fgc.exception_action WHERE exception_case_id = ? ORDER BY action_seq
                """, fixture.caseId());
        assertThat(history).hasSize(1);
        assertThat(history.getFirst().get("action_seq")).isEqualTo(1);
        assertThat(history.getFirst().get("action_type")).isEqualTo(scenario.name());
        var audit = jdbc.queryForList("""
                SELECT user_id, action_code, before_value::text, after_value::text, reason, request_id,
                       client_ip, policy_version_id, occurred_at IS NOT NULL AS occurred
                  FROM fgc.audit_log WHERE entity_type='EXCEPTION_CASE' AND entity_id=? ORDER BY audit_log_id
                """, fixture.caseId().toString());
        assertThat(audit).hasSize(1);
        assertThat(audit.getFirst().get("action_code")).isEqualTo("EXCEPTION_ACTION");
        List<Map<String, Object>> journal = List.of();
        List<Map<String, Object>> journalAudits = List.of();
        if (response instanceof JournalCorrectionActionResponse corrected) {
            assertThat(corrected.originalJournalHeaderId()).isEqualTo(fixture.original());
            assertThat(corrected.fromStatus().name()).isEqualTo("IN_REVIEW");
            assertThat(corrected.toStatus().name()).isEqualTo("RESOLVED");
            assertThat(corrected.actionType()).isEqualTo("CORRECT");
            assertThat(corrected.actionBy()).isEqualTo(actor);
            assertThat(corrected.actionByLoginId()).isEqualTo(login);
            assertThat(corrected.reason()).isEqualTo("원장 금액 정정");
            assertThat(corrected.evidenceRef()).isEqualTo("DOC-PERF");
            assertThat(corrected.actionSeq()).isEqualTo(1);
            assertThat(corrected.actionAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
            assertThat(corrected.reversalJournalHeaderId()).isPositive();
            assertThat(corrected.repostedJournalHeaderId()).isPositive();
            assertThat(jdbc.queryForObject("SELECT original_journal_header_id FROM fgc.journal_correction_group WHERE correction_group_key=?", Long.class, corrected.correctionGroupKey())).isEqualTo(fixture.original());
            journal = jdbc.queryForList("""
                    SELECT CASE WHEN h.journal_header_id=? THEN 'original' WHEN h.journal_type='REVERSAL' THEN 'reversal' ELSE 'repost' END AS role,
                           h.journal_type, h.status, h.revision_no, h.contract_id, h.policy_version_id, h.validation_run_id,
                           l.line_no, a.account_code, l.debit_amount, l.credit_amount, l.contract_id AS line_contract_id,
                           l.agent_id, l.payment_stage, l.commission_item_id, l.memo
                      FROM fgc.journal_header h JOIN fgc.journal_line l USING(journal_header_id)
                      JOIN fgc.journal_account a USING(journal_account_id)
                     WHERE h.journal_header_id=? OR h.correction_group_key=? ORDER BY role, l.line_no
                    """, fixture.original(), fixture.original(), corrected.correctionGroupKey());
            assertThat(journal).hasSize(6);
            journal.forEach(row -> {
                String role = (String) row.get("role");
                boolean firstLine = ((Number) row.get("line_no")).intValue() == 1;
                BigDecimal amount = new BigDecimal("repost".equals(role) ? "620000" : "650000");
                boolean debit = firstLine != "reversal".equals(role);
                assertThat((BigDecimal) row.get("debit_amount")).isEqualByComparingTo(debit ? amount : BigDecimal.ZERO);
                assertThat((BigDecimal) row.get("credit_amount")).isEqualByComparingTo(debit ? BigDecimal.ZERO : amount);
                assertThat(row.get("memo")).isNull();
                assertThat(row.get("agent_id")).isNull();
                assertThat(row.get("commission_item_id")).isNull();
            });
            assertThat(journal.stream().filter(row -> "original".equals(row.get("role"))))
                    .allSatisfy(row -> assertThat(row.get("status")).isEqualTo("REVERSED"));
            assertThat(count("SELECT count(*) FROM fgc.journal_header WHERE correction_group_key=? AND status='POSTED'", corrected.correctionGroupKey())).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT sum(debit_amount-credit_amount) FROM fgc.journal_line WHERE journal_header_id IN (?,?,?)", BigDecimal.class,
                    fixture.original(), corrected.reversalJournalHeaderId(), corrected.repostedJournalHeaderId())).isEqualByComparingTo(BigDecimal.ZERO);
            journalAudits = jdbc.queryForList("""
                    SELECT user_id, action_code, reason, request_id, policy_version_id,
                           before_value - 'journalHeaderId' AS before_value,
                           after_value - 'reversalJournalHeaderId' - 'repostedJournalHeaderId' - 'correctionGroupKey' AS after_value
                      FROM fgc.audit_log WHERE entity_type='JOURNAL_HEADER' AND entity_id=?
                    """, fixture.original().toString());
            assertThat(journalAudits).hasSize(1);
        }
        return Map.of("state", state, "history", history, "audit", audit, "journal", journal, "journalAudit", journalAudits);
    }

    private void report(String name, List<Sample> oldSamples, List<Sample> newSamples) {
        SqlCounts oldCounts = counts(oldSamples.getFirst().sql());
        SqlCounts newCounts = counts(newSamples.getFirst().sql());
        assertThat(oldCounts.total()).isPositive();
        assertThat(newCounts.total()).isPositive();
        if (!name.equals("correct")) {
            assertThat(oldCounts).isEqualTo(new SqlCounts(2, 2, 1, 0, 5));
        }
        oldSamples.forEach(sample -> { assertThat(counts(sample.sql())).isEqualTo(oldCounts); assertThat(sample.commits()).isEqualTo(1); });
        newSamples.forEach(sample -> { assertThat(counts(sample.sql())).isEqualTo(newCounts); assertThat(sample.commits()).isEqualTo(1); });
        log.info("CORRECTION_ACTION_PERF {} old_sql={} new_sql={} old_p50_ms={} new_p50_ms={} old_p95_ms={} new_p95_ms={} old_jdbc_p50_ms={} new_jdbc_p50_ms={} old_lock_sql_p50_ms={} new_lock_sql_p50_ms={} old_controlled_hold_p50_ms={} new_controlled_hold_p50_ms={} commits=1 samples={}",
                name, oldCounts, newCounts, percentile(oldSamples, Sample::nanos, .50), percentile(newSamples, Sample::nanos, .50),
                percentile(oldSamples, Sample::nanos, .95), percentile(newSamples, Sample::nanos, .95),
                percentile(oldSamples, Sample::sqlNanos, .50), percentile(newSamples, Sample::sqlNanos, .50),
                percentile(oldSamples, Sample::lockSqlNanos, .50), percentile(newSamples, Sample::lockSqlNanos, .50),
                percentile(oldSamples, Sample::controlledHoldNanos, .50), percentile(newSamples, Sample::controlledHoldNanos, .50), SAMPLES);
        log.info("CORRECTION_ACTION_SQL_OLD {} {}", name, oldSamples.getFirst().sql());
        log.info("CORRECTION_ACTION_SQL_NEW {} {}", name, newSamples.getFirst().sql());
    }

    private static String percentile(List<Sample> samples, ToLongFunction<Sample> value, double p) {
        long[] values = samples.stream().mapToLong(value).sorted().toArray();
        return String.format(Locale.ROOT, "%.3f", values[(int) Math.ceil(values.length * p) - 1] / 1_000_000.0);
    }

    private static SqlCounts counts(List<String> statements) {
        int select = 0, insert = 0, update = 0, other = 0;
        for (String statement : statements) {
            String normalized = statement.replaceAll("(?s)/\\*.*?\\*/", "").stripLeading().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("SELECT")) select++;
            else if (normalized.startsWith("INSERT")) insert++;
            else if (normalized.startsWith("UPDATE")) update++;
            else other++;
        }
        return new SqlCounts(select, insert, update, other, statements.size());
    }

    private SqlSessionTemplate baselineSession() throws Exception {
        Configuration configuration = new Configuration(new Environment("correction-action-baseline",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        String path = "correction-action-baseline/ExceptionCaseActionMapper.xml";
        try (var input = new ClassPathResource(path).getInputStream()) {
            new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
        }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    }

    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }
    private enum Scenario { ASSIGN, START_REVIEW, CORRECT }
    private record Fixture(Long caseId, Long original) { }
    private record Sample(long nanos, Object response, List<String> sql, int commits,
                          long sqlNanos, long lockSqlNanos, long controlledHoldNanos) { }
    private record SqlCounts(int select, int insert, int update, int other, int total) { }
    private static class Observation {
        private final List<String> sql = new ArrayList<>();
        private int commits;
        private long sqlNanos;
        private long lockSqlNanos;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfig {
        @Bean static BeanPostProcessor correctionPerformanceDataSource() {
            return new BeanPostProcessor() {
                @Override public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource source)) return bean;
                    return new DelegatingDataSource(source) {
                        @Override public Connection getConnection() throws SQLException { return observeConnection(super.getConnection()); }
                        @Override public Connection getConnection(String user, String password) throws SQLException { return observeConnection(super.getConnection(user, password)); }
                    };
                }
            };
        }
    }

    private static Connection observeConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
            try {
                Object result = method.invoke(connection, args);
                Observation observation = OBSERVATION.get();
                if (observation != null && "commit".equals(method.getName())) observation.commits++;
                if (result instanceof Statement statement && List.of("prepareStatement", "prepareCall", "createStatement").contains(method.getName())) {
                    String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                    return observeStatement(statement, sql);
                }
                return result;
            } catch (InvocationTargetException exception) { throw exception.getCause(); }
        });
    }

    private static Statement observeStatement(Statement statement, String preparedSql) {
        Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            Observation observation = OBSERVATION.get();
            boolean execute = observation != null && List.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch").contains(method.getName());
            String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : preparedSql;
            if (execute && sql == null) throw new IllegalStateException("Unobservable JDBC execution: " + method.getName());
            if (execute) observation.sql.add(sql.replaceAll("\\s+", " ").trim());
            long started = System.nanoTime();
            try { return method.invoke(statement, args); }
            catch (InvocationTargetException exception) { throw exception.getCause(); }
            finally {
                if (execute) {
                    long elapsed = System.nanoTime() - started;
                    observation.sqlNanos += elapsed;
                    if (sql.toLowerCase(Locale.ROOT).matches("(?s).*for\\s+(no\\s+key\\s+)?update.*")) observation.lockSqlNanos += elapsed;
                }
            }
        });
    }
}
