package com.susukkang.fgc.journal.repository;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.code.JournalHeaderStatus;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalDetailHeaderRow;
import com.susukkang.fgc.journal.dto.JournalDetailLineResponse;
import com.susukkang.fgc.journal.dto.JournalDetailLineRow;
import com.susukkang.fgc.journal.dto.JournalDetailResponse;
import com.susukkang.fgc.journal.dto.JournalImbalanceSearchResponse;
import com.susukkang.fgc.journal.dto.JournalListRow;
import com.susukkang.fgc.journal.dto.JournalSearchCriteria;
import com.susukkang.fgc.journal.dto.LedgerImbalanceRow;
import com.susukkang.fgc.journal.service.JournalDetailService;
import com.susukkang.fgc.journal.service.JournalImbalanceService;
import com.susukkang.fgc.journal.service.JournalSearchService;
import jakarta.persistence.EntityManagerFactory;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.hibernate.SessionFactory;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * f7a82c78의 MyBatis SQL과 현재 JPA 서비스를 동일 DB·입력에서 비교한다.
 * 30회 워밍업 후 100회 교차 실행하며 시간은 합격 기준으로 사용하지 않는다.
 * JDBC 실제 실행을 양쪽 모두 관찰하며 JSON 변환·검증·픽스처 생성 시간은 제외한다.
 * 테스트 전용 XML은 운영 mapper 경로 밖에 두고 로컬 SqlSessionFactory에서만 읽는다.
 *
 * @author hjKang
 * @since 2026-09-28
 */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.cache.use_query_cache=false",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@Import(JournalReadPerformanceTest.SqlObservationConfig.class)
@Transactional
@EnabledIfSystemProperty(named = "fgc.journal.performance", matches = "true")
class JournalReadPerformanceTest {
    private static final int WARMUPS = 30;
    private static final int SAMPLES = 100;
    private static final int HEADER_COUNT = 5000;
    private static final LocalDate FROM = LocalDate.of(2091, 7, 1);
    private static final LocalDate TO = FROM.plusDays(27);
    private static final String SEARCH = "com.susukkang.fgc.journal.mapper.JournalSearchMapper.";
    private static final String DETAIL = "com.susukkang.fgc.journal.mapper.JournalDetailMapper.";
    private static final String IMBALANCE = "com.susukkang.fgc.journal.mapper.JournalImbalanceMapper.";
    private static final ThreadLocal<List<String>> EXECUTED_SQL = new ThreadLocal<>();

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private JournalSearchService searchService;
    @Autowired private JournalDetailService detailService;
    @Autowired private JournalImbalanceService imbalanceService;
    @Autowired private LegacyReadFacade legacyReadFacade;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private final String marker = "JPERF-" + UUID.randomUUID().toString().replace("-", "");
    private final List<Long> runIds = new ArrayList<>();

    @Test
    void compareLegacyQueriesAndCurrentServices() throws Exception {
        assertThat(AopUtils.isAopProxy(legacyReadFacade)).as("Legacy read transaction proxy").isTrue();
        Fixture fixture = createFixture();
        SqlSessionTemplate baseline = baselineSession();
        JournalSearchCriteria all = new JournalSearchCriteria(FROM, TO, null, null, null, null);
        JournalSearchCriteria account = new JournalSearchCriteria(FROM, TO, null, marker, null, null);
        assertThat(searchService.search(all, 1, 20).totalElements()).isEqualTo(HEADER_COUNT);
        assertThat(searchService.search(all, 125, 20).content()).hasSize(20);
        PageResponse<JournalListRow> filtered = searchService.search(account, 1, 20);
        assertThat(filtered.totalElements()).isEqualTo(HEADER_COUNT / 2);
        assertThat(filtered.content()).hasSize(20).allSatisfy(row -> {
            assertThat(row.getDebitTotal()).isEqualByComparingTo("50");
            // 매칭 계정은 차변 두 줄뿐이다. 대변 다른 계정 합계도 반환해야 한다.
            assertThat(row.getCreditTotal()).isBetween(new BigDecimal("49"), new BigDecimal("50"));
        });
        JournalDetailResponse nullable = detailService.findByJournalHeaderId(fixture.nullableHeaderId());
        assertThat(nullable.lines()).hasSize(2);
        assertThat(nullable.contractId()).isNull();
        assertThat(nullable.createdBy()).isNull();
        assertThat(nullable.postedAt()).isNull();
        assertThat(nullable.lines().getFirst().agentId()).isNull();
        JournalDetailResponse correction = detailService.findByJournalHeaderId(fixture.originalHeaderId());
        assertThat(correction.reversedByJournalHeaderId()).isEqualTo(fixture.reversalHeaderId());
        assertThat(correction.repostedJournalHeaderId()).isEqualTo(fixture.repostHeaderId());
        assertThat(correction.status()).isEqualTo(JournalHeaderStatus.REVERSED);
        assertThat(imbalanceService.findImbalances(runIds.getFirst()).totalCount()).isEqualTo(33);

        System.out.printf(Locale.ROOT,
                "JOURNAL_PERF_DATA baseline=f7a82c78 fixture_headers=%d fixture_lines=%d fixture_runs=%d fixture_imbalances=%d measured_run_imbalances=33 total_headers=%d total_lines=%d warmups=%d samples=%d page_size=20 rollback=true%n",
                count("SELECT count(*) FROM fgc.journal_header WHERE description = ?", marker),
                count("SELECT count(*) FROM fgc.journal_line l JOIN fgc.journal_header h USING(journal_header_id) WHERE h.description = ?", marker),
                runIds.size(),
                count("SELECT count(*) FROM fgc.vw_journal_imbalance v JOIN fgc.journal_header h USING(journal_header_id) WHERE h.description = ?", marker),
                count("SELECT count(*) FROM fgc.journal_header"), count("SELECT count(*) FROM fgc.journal_line"),
                WARMUPS, SAMPLES);

        compare("list-page-1", 2, () -> legacyPage(baseline, all, 1), () -> searchService.search(all, 1, 20));
        compare("list-page-125", 2, () -> legacyPage(baseline, all, 125), () -> searchService.search(all, 125, 20));
        compare("list-account-filter", 2, () -> legacyPage(baseline, account, 1), () -> searchService.search(account, 1, 20));
        compare("detail-nullable", 2, () -> legacyDetail(baseline, fixture.nullableHeaderId()),
                () -> detailService.findByJournalHeaderId(fixture.nullableHeaderId()));
        compare("detail-correction", 2, () -> legacyDetail(baseline, fixture.originalHeaderId()),
                () -> detailService.findByJournalHeaderId(fixture.originalHeaderId()));
        compare("imbalances-by-run", 2, () -> legacyImbalances(baseline, runIds.getFirst()),
                () -> imbalanceService.findImbalances(runIds.getFirst()));
    }

    private PageResponse<JournalListRow> legacyPage(SqlSessionTemplate session, JournalSearchCriteria condition, int page) {
        Map<String, Object> params = new HashMap<>();
        params.put("from", condition.from());
        params.put("to", condition.to());
        params.put("journalType", condition.journalType());
        params.put("accountCode", condition.accountCode());
        params.put("contractId", condition.contractId());
        params.put("status", condition.status());
        params.put("offset", (page - 1) * 20);
        params.put("limit", 20);
        List<JournalListRow> rows = session.selectList(SEARCH + "search", params);
        long total = session.selectOne(SEARCH + "count", params);
        return PageResponse.of(rows, page, 20, total, "journalDate,desc");
    }

    // f7a82c78의 서비스 조립 순서와 계산을 보존하여 양쪽 모두 같은 응답까지 생성한다.
    private JournalDetailResponse legacyDetail(SqlSessionTemplate session, Long id) {
        Map<String, Object> params = Map.of("journalHeaderId", id);
        JournalDetailHeaderRow row = session.selectOne(DETAIL + "findHeaderById", params);
        List<JournalDetailLineRow> lineRows = session.selectList(DETAIL + "findLinesByHeaderId", params);
        List<JournalDetailLineResponse> lines = lineRows.stream().map(JournalDetailLineResponse::from).toList();
        BigDecimal debit = BigDecimal.ZERO;
        BigDecimal credit = BigDecimal.ZERO;
        for (JournalDetailLineRow line : lineRows) {
            debit = debit.add(line.getDebitAmount());
            credit = credit.add(line.getCreditAmount());
        }
        BigDecimal difference = debit.subtract(credit);
        boolean balanced = debit.signum() > 0 && credit.signum() > 0 && difference.signum() == 0;
        JournalType type = JournalType.valueOf(row.getJournalType());
        JournalHeaderStatus status = JournalHeaderStatus.valueOf(row.getStatus());
        return new JournalDetailResponse(
                row.getJournalHeaderId(), row.getJournalNo(), row.getJournalDate(), type, type.label(),
                row.getSourceEntityType(), row.getSourceEntityId(), row.getRevisionNo(),
                row.getContractId(), row.getContractNo(), row.getValidationRunId(), row.getPolicyVersionId(),
                row.getCorrectionGroupKey(), status, status.label(), row.getDescription(),
                row.getCreatedBy(), row.getCreatedAt(), row.getPostedBy(), row.getPostedAt(),
                row.getReversalOfId(), row.getReversalOfJournalNo(),
                row.getReversedByJournalHeaderId(), row.getReversedByJournalNo(),
                row.getRepostedJournalHeaderId(), row.getRepostedJournalNo(),
                debit, credit, difference, balanced, lines);
    }

    private JournalImbalanceSearchResponse legacyImbalances(SqlSessionTemplate session, Long runId) {
        Map<String, Object> params = Map.of("validationRunId", runId);
        // 기존 서비스도 ValidationRunMapper.findById를 먼저 호출했으므로 이 SQL을 함께 센다.
        Object run = session.selectOne("journalValidationBaseline.findById", params);
        if (run == null) throw new IllegalStateException("Fixture validation run is missing");
        List<LedgerImbalanceRow> rows = session.selectList(IMBALANCE + "findImbalances", params);
        return JournalImbalanceSearchResponse.of(rows, rows.size());
    }

    private SqlSessionTemplate baselineSession() throws Exception {
        Configuration configuration = new Configuration(new Environment("journal-baseline",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        for (String name : List.of("JournalSearchMapper.xml", "JournalDetailMapper.xml",
                "JournalImbalanceMapper.xml", "ValidationRunRead.xml")) {
            String path = "journal-baseline/" + name;
            try (var input = new ClassPathResource(path).getInputStream()) {
                new XMLMapperBuilder(input, configuration, path, configuration.getSqlFragments()).parse();
            }
        }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    }

    private void compare(String name, int expectedSqlCount, Supplier<?> oldRead, Supplier<?> newRead) {
        // 기존 서비스의 readOnly REQUIRED 참여 비용도 동일하게 포함한다.
        Supplier<?> transactionalOldRead = () -> legacyReadFacade.read(oldRead);
        // 응답 전체의 행 순서·총건수·금액·타입·null을 비교하며 검증 시간은 측정하지 않는다.
        assertThat(objectMapper.<JsonNode>valueToTree(newRead.get()))
                .as("%s: baseline response equality", name)
                .isEqualTo(objectMapper.<JsonNode>valueToTree(transactionalOldRead.get()));
        for (int i = 0; i < WARMUPS; i++) {
            if (i % 2 == 0) { transactionalOldRead.get(); newRead.get(); }
            else { newRead.get(); transactionalOldRead.get(); }
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long loadedBefore = statistics.getEntityLoadCount();
        List<Sample> oldSamples = new ArrayList<>();
        List<Sample> newSamples = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            if (i % 2 == 0) {
                oldSamples.add(measure(transactionalOldRead)); newSamples.add(measure(newRead));
            } else {
                newSamples.add(measure(newRead)); oldSamples.add(measure(transactionalOldRead));
            }
        }
        assertThat(oldSamples).allSatisfy(s -> assertThat(s.sql().size()).isEqualTo(expectedSqlCount));
        assertThat(newSamples).allSatisfy(s -> assertThat(s.sql().size()).isEqualTo(expectedSqlCount));
        long entityLoads = statistics.getEntityLoadCount() - loadedBefore;
        assertThat(entityLoads).isZero();
        System.out.printf(Locale.ROOT,
                "JOURNAL_PERF %s old_sql_min=%d old_sql_max=%d new_sql_min=%d new_sql_max=%d old_p50_ms=%.3f new_p50_ms=%.3f old_p95_ms=%.3f new_p95_ms=%.3f entity_loads=%d samples=%d%n",
                name, minSql(oldSamples), maxSql(oldSamples), minSql(newSamples), maxSql(newSamples),
                percentile(oldSamples, .50), percentile(newSamples, .50),
                percentile(oldSamples, .95), percentile(newSamples, .95), entityLoads, SAMPLES);
        System.out.println("JOURNAL_SQL_OLD " + name + " " + oldSamples.getFirst().sql());
        System.out.println("JOURNAL_SQL_NEW " + name + " " + newSamples.getFirst().sql());
    }

    private Sample measure(Supplier<?> read) {
        List<String> statements = new ArrayList<>();
        EXECUTED_SQL.set(statements);
        try {
            long start = System.nanoTime();
            Object result = read.get();
            long elapsed = System.nanoTime() - start;
            assertThat(result).isNotNull();
            return new Sample(elapsed, List.copyOf(statements));
        } finally {
            EXECUTED_SQL.remove();
        }
    }

    private static int minSql(List<Sample> samples) {
        return samples.stream().mapToInt(sample -> sample.sql().size()).min().orElseThrow();
    }

    private static int maxSql(List<Sample> samples) {
        return samples.stream().mapToInt(sample -> sample.sql().size()).max().orElseThrow();
    }

    private static double percentile(List<Sample> samples, double percentile) {
        long[] values = samples.stream().mapToLong(Sample::nanos).toArray();
        Arrays.sort(values);
        return values[(int) Math.ceil(values.length * percentile) - 1] / 1_000_000.0;
    }

    private Fixture createFixture() {
        Long contractId = jdbc.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
        assertThat(contractId).as("Flyway seed contract is required").isNotNull();
        Long debitId = jdbc.queryForObject("""
                INSERT INTO fgc.journal_account (account_code, account_name, normal_balance)
                VALUES (?, '원장 성능 비교 계정', 'DEBIT') RETURNING journal_account_id
                """, Long.class, marker);
        Long otherDebitId = jdbc.queryForObject("""
                SELECT journal_account_id FROM fgc.journal_account WHERE account_code = 'EXPECTED_RECEIVABLE'
                """, Long.class);
        Long creditId = jdbc.queryForObject("""
                SELECT journal_account_id FROM fgc.journal_account WHERE account_code = 'EXPECTED_INCOME'
                """, Long.class);
        for (int i = 0; i < 3; i++) {
            LocalDate month = FROM.plusMonths(i);
            runIds.add(jdbc.queryForObject("""
                    INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                    SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'MONTHLY', 'CREATED'
                      FROM fgc.validation_run WHERE validation_month = ? RETURNING validation_run_id
                    """, Long.class, month, month));
        }
        jdbc.update("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     revision_no, validation_run_id, contract_id, description)
                SELECT ? || '-' || n, ?::date + (n % 28), 'ADJUSTMENT', 'JOURNAL_PERF',
                       ? || '-' || n, 1, CASE n % 3 WHEN 0 THEN ? WHEN 1 THEN ? ELSE ? END,
                       CASE WHEN n % 4 = 0 THEN NULL ELSE ? END, ?
                  FROM generate_series(1, ?) n
                """, marker, FROM, marker, runIds.get(0), runIds.get(1), runIds.get(2),
                contractId, marker, HEADER_COUNT);
        jdbc.update("""
                INSERT INTO fgc.journal_line
                    (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                SELECT h.journal_header_id, line_no,
                       CASE WHEN line_no <= 2 THEN
                            CASE WHEN n % 2 = 0 THEN ? ELSE ? END ELSE ? END,
                       CASE line_no WHEN 1 THEN 30.25 WHEN 2 THEN 19.75 ELSE 0 END,
                       CASE line_no WHEN 3 THEN 40 WHEN 4 THEN
                            CASE WHEN n % 50 = 0 THEN 9 ELSE 10 END ELSE 0 END
                  FROM generate_series(1, ?) n
                  JOIN fgc.journal_header h ON h.journal_no = ? || '-' || n
                  CROSS JOIN generate_series(1, 4) line_no
                """, debitId, otherDebitId, creditId, HEADER_COUNT, marker);

        Long nullableId = insertHeader("nullable", null);
        insertBalancedLines(nullableId, debitId, creditId, false);
        Long originalId = insertHeader("original", contractId);
        insertBalancedLines(originalId, debitId, creditId, false);
        post(originalId);
        jdbc.update("""
                INSERT INTO fgc.journal_correction_group (correction_group_key, original_journal_header_id, reason)
                VALUES (?, ?, '원장 성능 비교 정정 연결')
                """, marker, originalId);
        Long reversalId = jdbc.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     contract_id, reversal_of_id, correction_group_key, description)
                VALUES (?, ?, 'REVERSAL', 'JOURNAL_HEADER', ?, ?, ?, ?, ?) RETURNING journal_header_id
                """, Long.class, marker + "-reversal", FROM.plusMonths(1), originalId.toString(),
                contractId, originalId, marker, marker);
        insertBalancedLines(reversalId, debitId, creditId, true);
        post(reversalId);
        jdbc.update("UPDATE fgc.journal_header SET status = 'REVERSED' WHERE journal_header_id = ?", originalId);
        Long repostId = jdbc.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     contract_id, revision_no, correction_group_key, description)
                SELECT ?, ?, journal_type, source_entity_type, source_entity_id,
                       contract_id, revision_no + 1, ?, ?
                  FROM fgc.journal_header WHERE journal_header_id = ? RETURNING journal_header_id
                """, Long.class, marker + "-repost", FROM.plusMonths(1), marker, marker, originalId);
        insertBalancedLines(repostId, debitId, creditId, false);
        post(repostId);
        refreshStatistics();
        assertThat(count("SELECT count(*) FROM fgc.journal_header WHERE description = ?", marker)).isEqualTo(5004);
        assertThat(count("SELECT count(*) FROM fgc.journal_line l JOIN fgc.journal_header h USING(journal_header_id) WHERE h.description = ?", marker))
                .isEqualTo(20008);
        return new Fixture(nullableId, originalId, reversalId, repostId);
    }

    private Long insertHeader(String suffix, Long contractId) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id, contract_id, description)
                VALUES (?, ?, 'ADJUSTMENT', 'JOURNAL_PERF', ?, ?, ?) RETURNING journal_header_id
                """, Long.class, marker + "-" + suffix, FROM.plusMonths(1), marker + "-" + suffix, contractId, marker);
    }

    private void insertBalancedLines(Long id, Long debitId, Long creditId, boolean reversal) {
        // 역순 삽입: 양쪽 상세 조회가 line_no 순서를 보존하는지도 비교한다.
        jdbc.update("""
                INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                VALUES (?, 2, ?, ?, ?), (?, 1, ?, ?, ?)
                """, id, creditId, reversal ? 10 : 0, reversal ? 0 : 10,
                id, debitId, reversal ? 0 : 10, reversal ? 10 : 0);
    }

    private void post(Long id) {
        jdbc.update("UPDATE fgc.journal_header SET status = 'POSTED' WHERE journal_header_id = ?", id);
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    private void refreshStatistics() {
        for (String table : List.of("journal_header", "journal_line", "journal_account", "journal_correction_group", "validation_run")) {
            jdbc.execute("ANALYZE fgc." + table);
        }
    }

    @AfterTransaction
    void verifyFixtureRollbackAndRefreshStatistics() {
        try {
            assertThat(count("SELECT count(*) FROM fgc.journal_header WHERE description = ?", marker)).isZero();
            assertThat(count("SELECT count(*) FROM fgc.journal_account WHERE account_code = ?", marker)).isZero();
            assertThat(count("SELECT count(*) FROM fgc.journal_correction_group WHERE correction_group_key = ?", marker)).isZero();
            for (Long runId : runIds) {
                assertThat(count("SELECT count(*) FROM fgc.validation_run WHERE validation_run_id = ?", runId)).isZero();
            }
            System.out.println("JOURNAL_PERF_CLEANUP fixture_rows_remaining=0 rollback_verified=true");
        } finally {
            refreshStatistics();
        }
    }

    private record Fixture(Long nullableHeaderId, Long originalHeaderId, Long reversalHeaderId, Long repostHeaderId) { }
    private record Sample(long nanos, List<String> sql) { }

    /** 이전 서비스와 같은 Spring 트랜잭션 프록시를 통해 SQL·응답 조립을 실행한다. */
    @Transactional(readOnly = true)
    public static class LegacyReadFacade {
        public <T> T read(Supplier<T> read) {
            return read.get();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlObservationConfig {
        @Bean
        LegacyReadFacade legacyJournalReadFacade() {
            return new LegacyReadFacade();
        }

        @Bean
        static BeanPostProcessor observedJournalDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource source)) return bean;
                    return new DelegatingDataSource(source) {
                        @Override public Connection getConnection() throws SQLException {
                            return observeConnection(super.getConnection());
                        }
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
                        if (result instanceof Statement statement && List.of("prepareStatement", "prepareCall", "createStatement")
                                .contains(method.getName())) {
                            String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                            return observeStatement(statement, sql);
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }

    private static Statement observeStatement(Statement statement, String preparedSql) {
        Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
        return (Statement) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            List<String> collected = EXECUTED_SQL.get();
            if (collected != null && List.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate",
                    "executeBatch", "executeLargeBatch").contains(method.getName())) {
                String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : preparedSql;
                if (sql == null) throw new IllegalStateException("Unobservable JDBC execution: " + method.getName());
                collected.add(sql.replaceAll("\\s+", " ").trim());
            }
            try { return method.invoke(statement, args); }
            catch (InvocationTargetException exception) { throw exception.getCause(); }
        });
    }
}
