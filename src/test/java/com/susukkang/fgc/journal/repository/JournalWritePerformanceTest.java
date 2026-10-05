package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.audit.entity.AuditLog;
import com.susukkang.fgc.audit.repository.AuditLogRepository;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalAccountRow;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalHeaderRow;
import com.susukkang.fgc.journal.dto.JournalLineDraft;
import com.susukkang.fgc.journal.service.JournalPersistenceService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Transactional;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * f7a82c78의 저장 알고리즘/SQL과 현재 실제 저장 서비스를 비교한다. 감사 저장은 기준 리비전에서도 JPA였다.
 * 10회 워밍업 + 40회 교차 실행. 검증·픽스처·draft 생성은 제외하고 서비스 호출과 실제 commit은 포함한다.
 * saveDraft 단독 호출과 외부 REQUIRED + 내부 REQUIRES_NEW 저장/기표를 각각 같은 조건으로 비교한다.
 * 배치 원천 검색/초안 생성은 측정 범위가 아니며, DB 트리거 내부 SQL은 JDBC 실행 횟수에 포함되지 않는다.
 * commit된 감사 로그는 삭제하지 않는다. 전용 폐기 DB에서만 실행하고 측정 후 DB 전체를 폐기한다.
 */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.jpa.properties.hibernate.cache.use_query_cache=false",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false"
})
@Import(JournalWritePerformanceTest.ObservationConfig.class)
@EnabledIfSystemProperty(named = "fgc.journal.write.performance", matches = "true")
class JournalWritePerformanceTest {
    private static final int WARMUPS = 10;
    private static final int SAMPLES = 40;
    private static final LocalDate DATE = LocalDate.of(2094, 6, 15);
    private static final String MAPPER = "com.susukkang.fgc.journal.mapper.JournalMapper.";
    private static final String ACCOUNT = "com.susukkang.fgc.journal.mapper.JournalAccountMapper.";
    private static final ThreadLocal<Observation> OBSERVATION = new ThreadLocal<>();

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JournalPersistenceService persistenceService;
    @Autowired private JournalHeaderRepository headerRepository;
    @Autowired private AuditLogRepository auditRepository;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ConstraintErrorCodeResolver constraintResolver;
    @Autowired private PostingTransaction postingTransaction;

    private final String marker = "JWPERF-" + UUID.randomUUID().toString().replace("-", "");
    private LegacyPersistence legacy;
    private Long actorId;
    private Long contractId;
    private int serial;
    private long expectedHeaders;
    private long expectedLines;

    // Flyway를 포함한 Spring 컨텍스트 초기화 전에 운영/개발 DB 주소를 거부한다.
    @DynamicPropertySource
    static void requireDisposableDatabase(DynamicPropertyRegistry registry) {
        String url = System.getenv("SPRING_DATASOURCE_URL");
        if (url == null || !url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/fgc_journal_write_perf_[a-z0-9_]+\\?currentSchema=fgc")) {
            throw new IllegalStateException("쓰기 성능 비교에는 loopback의 fgc_journal_write_perf_* 전용 DB가 필요합니다.");
        }
        registry.add("spring.datasource.url", () -> url);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Test
    void compareCommittedDraftAndPostingWrites() throws Exception {
        assertThat(jdbc.queryForObject("SELECT current_database()", String.class))
                .startsWith("fgc_journal_write_perf_");
        assertThat(AopUtils.isAopProxy(postingTransaction)).isTrue();
        actorId = jdbc.queryForObject("SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
        contractId = jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
        assertThat(actorId).as("Flyway seed actor").isNotNull();
        assertThat(contractId).as("Flyway seed contract").isNotNull();
        legacy = new LegacyPersistence(baselineSession(), transactionManager, auditRepository, constraintResolver);
        long headersBefore = count("SELECT count(*) FROM fgc.journal_header");
        long linesBefore = count("SELECT count(*) FROM fgc.journal_line");
        long auditsBefore = count("SELECT count(*) FROM fgc.audit_log");
        System.out.printf(Locale.ROOT,
                "JOURNAL_WRITE_PERF_DATA baseline=f7a82c78 marker=%s warmups=%d samples=%d transaction_commit_included=true trigger_internal_sql_included=false source_queries_included=false cleanup=drop_disposable_database headers_before=%d lines_before=%d audits_before=%d%n",
                marker, WARMUPS, SAMPLES, headersBefore, linesBefore, auditsBefore);

        compareNew("draft-2-lines", 2, false);
        compareNew("draft-20-lines", 20, false);
        compareNew("save-and-post-2-lines", 2, true);
        compareNew("save-and-post-20-lines", 20, true);
        compareAlreadyPosted();

        assertThat(count("SELECT count(*) FROM fgc.journal_header") - headersBefore).isEqualTo(expectedHeaders);
        assertThat(count("SELECT count(*) FROM fgc.journal_line") - linesBefore).isEqualTo(expectedLines);
        assertThat(count("SELECT count(*) FROM fgc.audit_log") - auditsBefore).isEqualTo(expectedHeaders);
        assertThat(count("SELECT count(*) FROM fgc.journal_header WHERE description = ?", marker)).isEqualTo(expectedHeaders);
        assertThat(count("SELECT count(*) FROM fgc.audit_log WHERE request_id = ?", marker)).isEqualTo(expectedHeaders);
        System.out.printf(Locale.ROOT,
                "JOURNAL_WRITE_PERF_ROWS committed_headers=%d committed_lines=%d committed_audits=%d expected_verified=true cleanup_required=drop_disposable_database%n",
                expectedHeaders, expectedLines, expectedHeaders);
    }

    private void compareNew(String name, int lineCount, boolean post) {
        List<Sample> oldSamples = new ArrayList<>();
        List<Sample> newSamples = new ArrayList<>();
        for (int i = -WARMUPS; i < SAMPLES; i++) {
            JournalHeaderDraft oldDraft = draft(lineCount);
            JournalHeaderDraft newDraft = draft(lineCount);
            Supplier<Outcome> oldCall = () -> legacyCall(oldDraft, post);
            Supplier<Outcome> newCall = () -> currentCall(newDraft, post);
            Sample oldSample;
            Sample newSample;
            if (i % 2 == 0) {
                oldSample = measure(oldCall);
                newSample = measure(newCall);
            } else {
                newSample = measure(newCall);
                oldSample = measure(oldCall);
            }
            // 전체 행의 의미·순서·null·감사를 비교한다. ID/번호/시각은 개별 검증 후 정규화한다.
            assertThat(snapshot(newDraft, newSample.outcome(), post))
                    .as("%s outcome equality, iteration %s", name, i)
                    .isEqualTo(snapshot(oldDraft, oldSample.outcome(), post));
            assertThat(oldSample.outcome().newlyPosted()).isEqualTo(post ? 1 : 0);
            assertThat(newSample.outcome().newlyPosted()).isEqualTo(post ? 1 : 0);
            expectedHeaders += 2;
            expectedLines += lineCount * 2L;
            if (i >= 0) {
                oldSamples.add(oldSample);
                newSamples.add(newSample);
            }
        }
        // 라인마다 활성 계정 SELECT 1 + INSERT 1. 감사 저장은 양쪽 모두 JPA다.
        report(name, oldSamples, newSamples, new SqlCounts(lineCount + 4, lineCount + 2, post ? 1 : 0, 0));
    }

    private void compareAlreadyPosted() {
        JournalHeaderDraft oldDraft = draft(2);
        JournalHeaderDraft newDraft = draft(2);
        Outcome oldSaved = legacyCall(oldDraft, true);
        Outcome newSaved = currentCall(newDraft, true);
        assertThat(snapshot(newDraft, newSaved, true)).isEqualTo(snapshot(oldDraft, oldSaved, true));
        expectedHeaders += 2;
        expectedLines += 4;
        long beforeHeaders = count("SELECT count(*) FROM fgc.journal_header");
        long beforeLines = count("SELECT count(*) FROM fgc.journal_line");
        long beforeAudits = count("SELECT count(*) FROM fgc.audit_log");
        List<Sample> oldSamples = new ArrayList<>();
        List<Sample> newSamples = new ArrayList<>();
        for (int i = -WARMUPS; i < SAMPLES; i++) {
            Sample oldSample;
            Sample newSample;
            if (i % 2 == 0) {
                oldSample = measure(() -> legacyCall(oldDraft, true));
                newSample = measure(() -> currentCall(newDraft, true));
            } else {
                newSample = measure(() -> currentCall(newDraft, true));
                oldSample = measure(() -> legacyCall(oldDraft, true));
            }
            assertThat(oldSample.outcome().row().getJournalHeaderId()).isEqualTo(oldSaved.row().getJournalHeaderId());
            assertThat(newSample.outcome().row().getJournalHeaderId()).isEqualTo(newSaved.row().getJournalHeaderId());
            assertThat(oldSample.outcome().row().getStatus()).isEqualTo("POSTED");
            assertThat(newSample.outcome().row().getStatus()).isEqualTo("POSTED");
            assertThat(oldSample.outcome().newlyPosted()).isZero();
            assertThat(newSample.outcome().newlyPosted()).isZero();
            if (i >= 0) { oldSamples.add(oldSample); newSamples.add(newSample); }
        }
        assertThat(count("SELECT count(*) FROM fgc.journal_header")).isEqualTo(beforeHeaders);
        assertThat(count("SELECT count(*) FROM fgc.journal_line")).isEqualTo(beforeLines);
        assertThat(count("SELECT count(*) FROM fgc.audit_log")).isEqualTo(beforeAudits);
        report("already-posted-rerun", oldSamples, newSamples, new SqlCounts(1, 0, 0, 0));
        System.out.println("JOURNAL_WRITE_PERF_IDEMPOTENCY extra_headers=0 extra_lines=0 extra_audits=0 newly_posted=0");
    }

    private Outcome legacyCall(JournalHeaderDraft draft, boolean post) {
        Supplier<Outcome> call = () -> {
            JournalHeaderRow row = legacy.saveDraft(draft, actorId, marker);
            int posted = 0;
            // 기준 리비전의 postAndCommit은 DRAFT일 때 UPDATE를 실행하고 1을 반환한다.
            if (post && "DRAFT".equals(row.getStatus())) {
                legacy.session.update(MAPPER + "markPosted", params("journalHeaderId", row.getJournalHeaderId(), "postedBy", actorId));
                posted = 1;
            }
            return new Outcome(row, posted);
        };
        return post ? postingTransaction.execute(call) : call.get();
    }

    private Outcome currentCall(JournalHeaderDraft draft, boolean post) {
        Supplier<Outcome> call = () -> {
            JournalHeaderRow row = persistenceService.saveDraft(draft, actorId, marker);
            int posted = post && "DRAFT".equals(row.getStatus())
                    ? headerRepository.markPosted(row.getJournalHeaderId(), actorId) : 0;
            return new Outcome(row, posted);
        };
        return post ? postingTransaction.execute(call) : call.get();
    }

    private JournalHeaderDraft draft(int lineCount) {
        List<JournalLineDraft> lines = new ArrayList<>();
        for (int i = 1; i <= lineCount; i++) {
            boolean debit = i % 2 == 1;
            lines.add(JournalLineDraft.builder().lineNo(i)
                    .accountCode(debit ? JournalAccountCode.EXPECTED_RECEIVABLE : JournalAccountCode.EXPECTED_INCOME)
                    .debitAmount(debit ? new BigDecimal("123.45") : BigDecimal.ZERO)
                    .creditAmount(debit ? BigDecimal.ZERO : new BigDecimal("123.45"))
                    .contractId(i == 1 ? contractId : null).memo("write benchmark line " + i).build());
        }
        return JournalHeaderDraft.builder().journalType(JournalType.ADJUSTMENT).journalDate(DATE)
                .sourceEntityType("JOURNAL_WRITE_PERF").sourceEntityId(marker + "-" + ++serial)
                .revisionNo(1).contractId(contractId).description(marker).lines(lines).build();
    }

    private Map<String, Object> snapshot(JournalHeaderDraft draft, Outcome outcome, boolean posted) {
        JournalHeaderRow row = outcome.row();
        Long id = row.getJournalHeaderId();
        assertThat(id).isPositive();
        assertThat(row.getJournalNo()).matches("JV-2094-06-[0-9]{4}");
        assertThat(row.getCreatedAt()).isNotNull();
        assertThat(row.getSourceEntityId()).isEqualTo(draft.getSourceEntityId());
        assertThat(row.getJournalDate()).isEqualTo(DATE);
        assertThat(row.getJournalType()).isEqualTo("ADJUSTMENT");
        assertThat(row.getSourceEntityType()).isEqualTo("JOURNAL_WRITE_PERF");
        assertThat(row.getRevisionNo()).isEqualTo(1);
        assertThat(row.getContractId()).isEqualTo(contractId);
        assertThat(row.getValidationRunId()).isNull();
        assertThat(row.getPolicyVersionId()).isNull();
        assertThat(row.getDescription()).isEqualTo(marker);
        assertThat(row.getCreatedBy()).isEqualTo(actorId);
        assertThat(row.getStatus()).isEqualTo("DRAFT");
        assertThat(row.getPostedBy()).isNull();
        assertThat(row.getPostedAt()).isNull();
        Map<String, Object> header = jdbc.queryForMap("""
                SELECT journal_date, journal_type, source_entity_type, source_entity_id, revision_no,
                       validation_run_id, contract_id, policy_version_id, status, description, created_by,
                       posted_by, created_at IS NOT NULL AS has_created_at, posted_at IS NOT NULL AS has_posted_at,
                       reversal_of_id, correction_group_key, journal_no
                  FROM fgc.journal_header WHERE journal_header_id = ?
                """, id);
        assertThat(header.remove("journal_no")).isEqualTo(row.getJournalNo());
        assertThat(header.remove("source_entity_id")).isEqualTo(draft.getSourceEntityId());
        assertThat(header.get("status")).isEqualTo(posted ? "POSTED" : "DRAFT");
        assertThat(header.get("has_created_at")).isEqualTo(true);
        assertThat(header.get("has_posted_at")).isEqualTo(posted);
        assertThat(header.get("posted_by")).isEqualTo(posted ? actorId : null);
        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT l.line_no, a.account_code, l.debit_amount, l.credit_amount, l.contract_id,
                       l.agent_id, l.payment_stage, l.commission_item_id, l.memo
                  FROM fgc.journal_line l JOIN fgc.journal_account a USING(journal_account_id)
                 WHERE l.journal_header_id = ? ORDER BY l.line_no
                """, id);
        assertThat(lines).hasSize(draft.getLines().size());
        for (int i = 0; i < lines.size(); i++) {
            Map<String, Object> actual = lines.get(i);
            JournalLineDraft expected = draft.getLines().get(i);
            assertThat(actual.get("line_no")).isEqualTo(expected.getLineNo());
            assertThat(actual.get("account_code")).isEqualTo(expected.getAccountCode().name());
            assertThat((BigDecimal) actual.get("debit_amount")).isEqualByComparingTo(expected.getDebitAmount());
            assertThat((BigDecimal) actual.get("credit_amount")).isEqualByComparingTo(expected.getCreditAmount());
            assertThat(actual.get("contract_id")).isEqualTo(expected.getContractId());
            assertThat(actual.get("memo")).isEqualTo(expected.getMemo());
            assertThat(actual.get("agent_id")).isNull();
            assertThat(actual.get("payment_stage")).isNull();
            assertThat(actual.get("commission_item_id")).isNull();
        }
        List<Map<String, Object>> audits = jdbc.queryForList("""
                SELECT user_id, action_code, entity_type, entity_id, before_value, after_value, reason,
                       request_id, client_ip, policy_version_id, occurred_at IS NOT NULL AS has_occurred_at
                  FROM fgc.audit_log WHERE entity_type = 'JOURNAL_HEADER' AND entity_id = ?
                """, id.toString());
        assertThat(audits).hasSize(1);
        Map<String, Object> audit = audits.getFirst();
        assertThat(audit.remove("entity_id")).isEqualTo(id.toString());
        assertThat(audit.remove("reason")).isEqualTo(reason(draft));
        assertThat(audit.get("action_code")).isEqualTo("JOURNAL_DRAFT_SAVED");
        assertThat(audit.get("user_id")).isEqualTo(actorId);
        assertThat(audit.get("request_id")).isEqualTo(marker);
        assertThat(audit.get("has_occurred_at")).isEqualTo(true);
        return Map.of("header", header, "lines", lines, "audit", audit);
    }

    private Sample measure(Supplier<Outcome> call) {
        Observation observation = new Observation();
        OBSERVATION.set(observation);
        try {
            long start = System.nanoTime();
            Outcome result = call.get();
            long elapsed = System.nanoTime() - start;
            return new Sample(elapsed, result, List.copyOf(observation.sql), observation.commits);
        } finally {
            OBSERVATION.remove();
        }
    }

    private void report(String name, List<Sample> oldSamples, List<Sample> newSamples, SqlCounts expected) {
        for (List<Sample> samples : List.of(oldSamples, newSamples)) {
            assertThat(samples).hasSize(SAMPLES).allSatisfy(sample ->
                    assertThat(sqlCounts(sample.sql())).as("%s SQL: %s", name, sample.sql()).isEqualTo(expected));
        }
        System.out.printf(Locale.ROOT,
                "JOURNAL_WRITE_PERF %s old_select=%d old_insert=%d old_update=%d old_other=%d old_total=%d new_select=%d new_insert=%d new_update=%d new_other=%d new_total=%d old_p50_ms=%.3f new_p50_ms=%.3f old_p95_ms=%.3f new_p95_ms=%.3f old_commit_min=%d old_commit_max=%d new_commit_min=%d new_commit_max=%d samples=%d%n",
                name, expected.select(), expected.insert(), expected.update(), expected.other(), expected.total(),
                expected.select(), expected.insert(), expected.update(), expected.other(), expected.total(),
                percentile(oldSamples, .50), percentile(newSamples, .50), percentile(oldSamples, .95), percentile(newSamples, .95),
                minCommits(oldSamples), maxCommits(oldSamples), minCommits(newSamples), maxCommits(newSamples), SAMPLES);
        System.out.println("JOURNAL_WRITE_SQL_OLD " + name + " " + oldSamples.getFirst().sql());
        System.out.println("JOURNAL_WRITE_SQL_NEW " + name + " " + newSamples.getFirst().sql());
    }

    private static SqlCounts sqlCounts(List<String> sql) {
        int select = 0, insert = 0, update = 0, other = 0;
        for (String statement : sql) {
            String normalized = statement.replaceAll("(?s)/\\*.*?\\*/", "").stripLeading().toUpperCase(Locale.ROOT);
            if (normalized.startsWith("SELECT")) select++;
            else if (normalized.startsWith("INSERT")) insert++;
            else if (normalized.startsWith("UPDATE")) update++;
            else other++;
        }
        return new SqlCounts(select, insert, update, other);
    }

    private static int minCommits(List<Sample> samples) { return samples.stream().mapToInt(Sample::commits).min().orElseThrow(); }
    private static int maxCommits(List<Sample> samples) { return samples.stream().mapToInt(Sample::commits).max().orElseThrow(); }

    private static double percentile(List<Sample> samples, double percentile) {
        long[] values = samples.stream().mapToLong(Sample::nanos).sorted().toArray();
        return values[(int) Math.ceil(values.length * percentile) - 1] / 1_000_000.0;
    }

    private long count(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    private SqlSessionTemplate baselineSession() throws Exception {
        Configuration configuration = new Configuration(new Environment("journal-write-baseline",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        for (String name : List.of("JournalMapper.xml", "JournalAccountMapper.xml")) {
            String path = "journal-write-baseline/" + name;
            try (var input = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    }

    private static Map<String, Object> params(Object... pairs) {
        Map<String, Object> result = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }

    private static String reason(JournalHeaderDraft draft) {
        return "journalType=" + draft.getJournalType() + ",sourceEntityType=" + draft.getSourceEntityType()
                + ",sourceEntityId=" + draft.getSourceEntityId() + ",validationRunId=" + draft.getValidationRunId();
    }

    /**
     * f7a82c78 JournalPersistenceServiceImpl 복원. 삭제된 Insert DTO만 map으로 대체했다.
     * XML SQL/GeneratedKeys, 선행 검증, 재시도/원천 경합 처리, 감사 JPA와 최종 재조회는 그대로다.
     */
    private static class LegacyPersistence {
        private final SqlSessionTemplate session;
        private final TransactionTemplate requiresNew;
        private final AuditLogRepository audits;
        private final ConstraintErrorCodeResolver resolver;

        LegacyPersistence(SqlSessionTemplate session, PlatformTransactionManager manager,
                          AuditLogRepository audits, ConstraintErrorCodeResolver resolver) {
            this.session = session;
            this.audits = audits;
            this.resolver = resolver;
            requiresNew = new TransactionTemplate(manager);
            requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }

        JournalHeaderRow saveDraft(JournalHeaderDraft draft, Long createdBy, String requestId) {
            Map<String, Object> source = params("journalType", draft.getJournalType().name(),
                    "sourceEntityType", draft.getSourceEntityType(), "sourceEntityId", draft.getSourceEntityId(),
                    "revisionNo", draft.getRevisionNo());
            JournalHeaderRow existing = session.selectOne(MAPPER + "findBySourceKey", source);
            if (existing != null) return existing;
            if (Boolean.TRUE.equals(session.selectOne(MAPPER + "existsPostedForSource", source))) {
                throw new FgcBusinessException(FgcErrorCode.LEDG_002, source);
            }
            List<Long> accountIds = new ArrayList<>();
            for (JournalLineDraft line : draft.getLines()) {
                JournalAccountRow account = session.selectOne(ACCOUNT + "findActiveByCode", params("accountCode", line.getAccountCode().name()));
                if (account == null) throw new FgcBusinessException(FgcErrorCode.JOURNAL_001, Map.of("accountCode", line.getAccountCode()));
                accountIds.add(account.getJournalAccountId());
            }
            BigDecimal debit = draft.getLines().stream().map(JournalLineDraft::getDebitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal credit = draft.getLines().stream().map(JournalLineDraft::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (debit.compareTo(credit) != 0) throw new FgcBusinessException(FgcErrorCode.LEDG_001, Map.of("c", debit.subtract(credit).abs()));
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    return requiresNew.execute(status -> attemptSave(draft, source, accountIds, createdBy, requestId));
                } catch (DataIntegrityViolationException exception) {
                    if (matchesConstraint(exception, "uq_journal_source_revision")) {
                        JournalHeaderRow winner = session.selectOne(MAPPER + "findBySourceKey", source);
                        if (winner != null) return winner;
                        throw exception;
                    }
                    if (!matchesConstraint(exception, "uq_journal_no") || attempt == 3) throw exception;
                }
            }
            throw new IllegalStateException("unreachable retry exit");
        }

        private boolean matchesConstraint(DataIntegrityViolationException exception, String name) {
            var actual = resolver.extractConstraintName(exception);
            if (actual.isPresent()) return name.equals(actual.get());
            Throwable root = exception;
            while (root.getCause() != null) root = root.getCause();
            return root.getMessage() != null && root.getMessage().toLowerCase(Locale.ROOT).contains(name);
        }

        private JournalHeaderRow attemptSave(JournalHeaderDraft draft, Map<String, Object> source,
                                            List<Long> accountIds, Long actor, String requestId) {
            String year = String.valueOf(draft.getJournalDate().getYear());
            String month = String.format("%02d", draft.getJournalDate().getMonthValue());
            int seq = session.selectOne(MAPPER + "findNextJournalSeq", params("year", year, "month", month));
            if (seq > 9999) throw new IllegalStateException("journal_no monthly sequence limit exceeded");
            Map<String, Object> header = params("journalNo", String.format("JV-%s-%s-%04d", year, month, seq),
                    "journalDate", draft.getJournalDate(), "journalType", draft.getJournalType().name(),
                    "sourceEntityType", draft.getSourceEntityType(), "sourceEntityId", draft.getSourceEntityId(),
                    "revisionNo", draft.getRevisionNo(), "validationRunId", draft.getValidationRunId(),
                    "contractId", draft.getContractId(), "policyVersionId", draft.getPolicyVersionId(),
                    "reversalOfId", null, "correctionGroupKey", null, "description", draft.getDescription(), "createdBy", actor);
            session.insert(MAPPER + "insert", header);
            Long id = ((Number) header.get("journalHeaderId")).longValue();
            for (int i = 0; i < draft.getLines().size(); i++) {
                JournalLineDraft line = draft.getLines().get(i);
                session.insert(MAPPER + "insertLine", params("journalHeaderId", id, "lineNo", line.getLineNo(),
                        "journalAccountId", accountIds.get(i), "debitAmount", line.getDebitAmount(), "creditAmount", line.getCreditAmount(),
                        "contractId", line.getContractId(), "agentId", line.getAgentId(), "paymentStage",
                        line.getPaymentStage() == null ? null : line.getPaymentStage().name(),
                        "commissionItemId", line.getCommissionItemId(), "memo", line.getMemo()));
            }
            audits.saveAndFlush(AuditLog.create(actor, "JOURNAL_DRAFT_SAVED", "JOURNAL_HEADER", id.toString(),
                    null, null, reason(draft), requestId, null, null));
            return session.selectOne(MAPPER + "findBySourceKey", source);
        }
    }

    public record Outcome(JournalHeaderRow row, int newlyPosted) { }
    private record Sample(long nanos, Outcome outcome, List<String> sql, int commits) { }
    private record SqlCounts(int select, int insert, int update, int other) {
        int total() { return select + insert + update + other; }
    }
    private static class Observation {
        private final List<String> sql = new ArrayList<>();
        private int commits;
    }

    public static class PostingTransaction {
        @Transactional
        public Outcome execute(Supplier<Outcome> call) { return call.get(); }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfig {
        @Bean PostingTransaction writePerformancePostingTransaction() { return new PostingTransaction(); }

        @Bean
        static BeanPostProcessor observedWriteDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource source)) return bean;
                    return new DelegatingDataSource(source) {
                        @Override public Connection getConnection() throws SQLException { return observeConnection(super.getConnection()); }
                        @Override public Connection getConnection(String user, String password) throws SQLException {
                            return observeConnection(super.getConnection(user, password));
                        }
                    };
                }
            };
        }
    }

    private static Connection observeConnection(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
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
            if (observation != null && List.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch").contains(method.getName())) {
                String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : preparedSql;
                if (sql == null) throw new IllegalStateException("Unobservable JDBC execution: " + method.getName());
                observation.sql.add(sql.replaceAll("\\s+", " ").trim());
            }
            try { return method.invoke(statement, args); }
            catch (InvocationTargetException exception) { throw exception.getCause(); }
        });
    }
}
