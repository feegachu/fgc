package com.susukkang.fgc.cap.performance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.cap.dto.*;
import com.susukkang.fgc.cap.performance.baseline.*;
import com.susukkang.fgc.cap.repository.*;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.transaction.performance.PaymentJdbcObservation;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootVersion;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : #372 조회 전환 전 Mapper와 현재 저장소를 동일 데이터·트랜잭션에서 교대 측정한다.
 * 검증·정렬·보고서 생성은 타이밍 밖에 두며 시간 자체는 합격 기준으로 사용하지 않는다.
 * JDBC 관측 시간에는 드라이버와 래퍼 전달 비용이 포함되므로 DB 서버 실행 시간으로 해석하지 않는다.
 *
 * @author hjKang
 * @since 2026-10-02
 * @version 1.0
 */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "spring.jpa.properties.hibernate.cache.use_query_cache=false",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "mybatis.configuration.local-cache-scope=STATEMENT",
        "mybatis.configuration.cache-enabled=false",
        "logging.level.org.hibernate.SQL=OFF",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@Import(PaymentJdbcObservation.Config.class)
@Transactional
@EnabledIfSystemProperty(named = "fgc.cap.performance", matches = "true")
class CapReferenceReadPerformanceTest {
    private static final Logger log = LoggerFactory.getLogger(CapReferenceReadPerformanceTest.class);
    // 비교 기준·재현 방법·측정 기록: https://www.notion.so/3ed7211a7b30815f8e4bd39f5ce604ab
    // 별도 문서 리소스 없이도 비교 테스트를 실행하도록 기준 커밋을 코드와 보고서에 보존한다.
    private static final String BASELINE_SHA = "8941047fc4b52e7e698f925cbc41467f8689c6d8";
    private static final int WARMUPS = Integer.getInteger("fgc.cap.performance.warmups", 25);
    private static final int SAMPLES = Integer.getInteger("fgc.cap.performance.samples", 80);
    private static final String ROUND = System.getProperty("fgc.cap.performance.round", "comparison");
    private static final boolean CURRENT_FIRST = Boolean.getBoolean("fgc.cap.performance.currentFirst");
    private static final String CONTRACT_PREFIX = "CAP-PERF-372-";
    private static final String PAYMENT_PREFIX = "CAP-PERF-372-PAY-";
    private static final int CONTRACTS = 50;
    private static final int TRANSACTIONS_PER_CONTRACT = 24;
    private static final PaymentStage STAGE = PaymentStage.INSURER_TO_GA;
    private static final List<String> FIXTURE_TABLES = List.of("insurance_contract", "schedule_header",
            "schedule_line", "commission_transaction", "transaction_attribution");
    private static volatile Object blackhole;

    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper objectMapper;
    @Autowired EntityManager entityManager;
    @Autowired EntityManagerFactory entityManagerFactory;
    @Autowired CapContractQueryRepository contracts;
    @Autowired CapRuleQueryRepository rules;
    @Autowired CapScheduleAmountQueryRepository schedules;
    @Autowired RefundRateQueryRepository refunds;
    @Autowired CapIncludedAmountQueryRepository includedAmounts;

    private final Map<String, Object> metadata = new LinkedHashMap<>();
    private final List<Sample> samples = new ArrayList<>();
    private final Map<String, LinkedHashSet<String>> rawSql = new LinkedHashMap<>();
    private Map<String, Long> rowsBefore;

    @Test
    void comparesAllReferenceReadsAndRepresentativeMonthlyBatches() throws Exception {
        assertThat(WARMUPS).isGreaterThanOrEqualTo(0);
        assertThat(SAMPLES).isGreaterThanOrEqualTo(5);
        assertThat(ROUND).matches("[A-Za-z0-9_-]+");
        rowsBefore = rowCounts();
        try {
            Baseline baseline = baseline();
            Fixture fixture = insertFixture();
            recordMetadata(fixture);
            analyzeFixtureTables();
            entityManager.flush();
            entityManager.clear();
            List<Benchmark> benchmarks = benchmarks(baseline, fixture);
            metadata.put("benchmarkOrder", benchmarks.stream().map(Benchmark::name).toList());
            metadata.put("status", "RUNNING");
            for (Benchmark benchmark : benchmarks) {
                compare(benchmark);
            }
            metadata.put("status", "PASSED");
        } catch (Exception | AssertionError failure) {
            metadata.put("status", "FAILED");
            metadata.put("failure", failure.toString());
            throw failure;
        } finally {
            metadata.put("completedAt", Instant.now().toString());
            writeReports();
        }
    }

    private List<Benchmark> benchmarks(Baseline old, Fixture f) {
        CapContractView c = f.contract();
        List<Benchmark> result = new ArrayList<>();
        result.add(new Benchmark("contract", 1, () -> old.contracts().findById(c.getContractId()),
                () -> contracts.findCapViewByContractId(c.getContractId())));
        result.add(new Benchmark("rule-set", 1, () -> old.rules().findApplicableRuleSet(STAGE.name(), c.getContractDate(),
                c.getInsurerId(), c.getProductGroupCode(), c.getChannelCode()),
                () -> rules.findApplicableRuleSet(STAGE.name(), c.getContractDate(), c.getInsurerId(),
                        c.getProductGroupCode(), c.getChannelCode())));
        result.add(new Benchmark("rule-items", 1, () -> old.rules().findRuleItems(f.ruleId()),
                () -> rules.findRuleItems(f.ruleId())));
        result.add(new Benchmark("schedule", 1, () -> old.schedules().findFirstYearScheduleAmounts(c.getContractId(), STAGE.name(), 12),
                () -> schedules.findFirstYearScheduleAmounts(c.getContractId(), STAGE.name(), 12)));
        result.add(new Benchmark("refund-table", 1, () -> old.refunds().findApplicableTable(c.getInsurerId(), c.getProductId(),
                c.getPaymentTermMonths(), c.getChannelCode(), c.getContractDate()),
                () -> refunds.findApplicableTable(c.getInsurerId(), c.getProductId(), c.getPaymentTermMonths(),
                        c.getChannelCode(), c.getContractDate())));
        result.add(new Benchmark("refund-month", 1, () -> old.refunds().findRateAtMonth(f.refundTableId(), 12),
                () -> refunds.findRateAtMonth(f.refundTableId(), 12)));
        result.add(new Benchmark("included-pre-confirm", 1,
                () -> old.included().sumIncludedAmountByContractAndAgent(c.getContractId(), f.currentPaymentId(), STAGE),
                () -> includedAmounts.sumIncludedAmountByContractAndAgent(c.getContractId(), f.currentPaymentId(), STAGE)));
        result.add(new Benchmark("included-confirmed", 1,
                () -> old.included().sumConfirmedIncludedAmountByContractAndAgent(c.getContractId(), STAGE),
                () -> includedAmounts.sumConfirmedIncludedAmountByContractAndAgent(c.getContractId(), STAGE)));
        result.add(new Benchmark("calculation-input-bundle", 6,
                () -> inputBundle(old, c.getContractId()), () -> inputBundle(null, c.getContractId())));
        for (int size : List.of(20, 50)) {
            List<Long> ids = f.contractIds().subList(0, size);
            result.add(new Benchmark("monthly-batch-" + size, size,
                    () -> ids.stream().map(id -> old.included().sumConfirmedIncludedAmountByContractAndAgent(id, STAGE)).toList(),
                    () -> ids.stream().map(id -> includedAmounts.sumConfirmedIncludedAmountByContractAndAgent(id, STAGE)).toList()));
        }
        return result;
    }

    private InputBundle inputBundle(Baseline old, Long contractId) {
        CapContractView c = old == null ? contracts.findCapViewByContractId(contractId) : old.contracts().findById(contractId);
        CapRuleSetView rule = old == null
                ? rules.findApplicableRuleSet(STAGE.name(), c.getContractDate(), c.getInsurerId(), c.getProductGroupCode(), c.getChannelCode())
                : old.rules().findApplicableRuleSet(STAGE.name(), c.getContractDate(), c.getInsurerId(), c.getProductGroupCode(), c.getChannelCode());
        List<CapRuleItemView> items = old == null ? rules.findRuleItems(rule.getCapRuleSetId()) : old.rules().findRuleItems(rule.getCapRuleSetId());
        List<ScheduleAmountView> lines = old == null
                ? schedules.findFirstYearScheduleAmounts(contractId, STAGE.name(), rule.getFirstYearMonths())
                : old.schedules().findFirstYearScheduleAmounts(contractId, STAGE.name(), rule.getFirstYearMonths());
        RefundRateTableView table = old == null
                ? refunds.findApplicableTable(c.getInsurerId(), c.getProductId(), c.getPaymentTermMonths(), c.getChannelCode(), c.getContractDate())
                : old.refunds().findApplicableTable(c.getInsurerId(), c.getProductId(), c.getPaymentTermMonths(), c.getChannelCode(), c.getContractDate());
        BigDecimal rate = old == null ? refunds.findRateAtMonth(table.getRefundRateTableId(), 12)
                : old.refunds().findRateAtMonth(table.getRefundRateTableId(), 12);
        return new InputBundle(c, rule, items, lines, table, rate);
    }

    private void compare(Benchmark benchmark) {
        assertEquivalent(benchmark);
        for (int pair = 0; pair < WARMUPS; pair++) {
            if (currentFirst(pair)) { blackhole = benchmark.current().get(); blackhole = benchmark.baseline().get(); }
            else { blackhole = benchmark.baseline().get(); blackhole = benchmark.current().get(); }
        }
        for (int pair = 0; pair < SAMPLES; pair++) {
            if (currentFirst(pair)) {
                measure(benchmark, "current", pair, 0, benchmark.current());
                measure(benchmark, "baseline", pair, 1, benchmark.baseline());
            } else {
                measure(benchmark, "baseline", pair, 0, benchmark.baseline());
                measure(benchmark, "current", pair, 1, benchmark.current());
            }
        }
        assertEquivalent(benchmark);
        log.info("CAP_PERF round={} operation={} samplesPerSide={} expectedSqlCount={} completed",
                ROUND, benchmark.name(), SAMPLES, benchmark.expectedSqlCount());
    }

    private boolean currentFirst(int pair) {
        return (pair % 2 == 0) == CURRENT_FIRST;
    }

    private void measure(Benchmark benchmark, String side, int pair, int order, Supplier<?> operation) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long loadsBefore = statistics.getEntityLoadCount();
        long writesBefore = entityWrites(statistics);
        PaymentJdbcObservation.start();
        long elapsed;
        PaymentJdbcObservation.Snapshot observation;
        try {
            long started = System.nanoTime();
            blackhole = operation.get();
            elapsed = System.nanoTime() - started;
        } finally {
            observation = PaymentJdbcObservation.stop();
        }
        long dmlCount = observation.executions().stream()
                .filter(sql -> sql.sql().stripLeading().toLowerCase(Locale.ROOT).matches("(?s)^(insert|update|delete|merge)\\b.*"))
                .count();
        Sample sample = new Sample(benchmark.name(), side, pair, order, elapsed, observation.jdbcNanos(),
                observation.prepareNanos(), observation.executeNanos(), observation.resultSetNanos(), observation.otherJdbcNanos(),
                observation.executions().size(), dmlCount, statistics.getEntityLoadCount() - loadsBefore,
                entityWrites(statistics) - writesBefore);
        samples.add(sample);
        LinkedHashSet<String> statements = rawSql.computeIfAbsent(benchmark.name() + "/" + side, key -> new LinkedHashSet<>());
        observation.executions().forEach(sql -> statements.add(sql.sql()));
        // 타이머와 JDBC 관측을 종료한 뒤 행위 불변 조건을 검증한다.
        assertThat(sample.sqlCount()).as("%s/%s SQL count", benchmark.name(), side).isEqualTo(benchmark.expectedSqlCount());
        assertThat(sample.entityLoads()).as("%s/%s entity loads", benchmark.name(), side).isZero();
        assertThat(sample.entityWrites()).isZero();
        assertThat(sample.dmlCount()).isZero();
        assertThat(observation.executions()).allSatisfy(sql ->
                assertThat(sql.sql().stripLeading().toLowerCase(Locale.ROOT)).startsWith("select"));
    }

    private long entityWrites(Statistics statistics) {
        return statistics.getEntityInsertCount() + statistics.getEntityUpdateCount() + statistics.getEntityDeleteCount();
    }

    private void assertEquivalent(Benchmark benchmark) {
        assertThat(canonical(objectMapper.valueToTree(benchmark.current().get())))
                .as("%s all DTO fields, values and nulls", benchmark.name())
                .isEqualTo(canonical(objectMapper.valueToTree(benchmark.baseline().get())));
    }

    private JsonNode canonical(JsonNode value) {
        if (value.isArray()) {
            List<JsonNode> children = new ArrayList<>();
            value.forEach(child -> children.add(canonical(child)));
            children.sort(Comparator.comparing(JsonNode::toString));
            ArrayNode result = objectMapper.createArrayNode();
            children.forEach(result::add);
            return result;
        }
        if (value.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            value.fields().forEachRemaining(field -> result.set(field.getKey(), canonical(field.getValue())));
            return result;
        }
        return value;
    }

    private Baseline baseline() throws IOException {
        Configuration config = new Configuration(new Environment("cap-baseline-" + BASELINE_SHA,
                new SpringManagedTransactionFactory(), dataSource));
        config.setMapUnderscoreToCamelCase(true);
        config.setLocalCacheScope(LocalCacheScope.STATEMENT);
        config.setCacheEnabled(false);
        // 기준 application.yml에는 사용자 정의 type handler가 없으며 PaymentStage는 기본 EnumTypeHandler를 쓴다.
        for (String name : List.of("CapContractMapper", "CapRuleMapper", "CapScheduleAmountMapper", "RefundRateMapper", "CapIncludedAmountMapper")) {
            String resource = "cap-baseline/" + name + ".xml";
            try (var input = new ClassPathResource(resource).getInputStream()) {
                new XMLMapperBuilder(input, config, resource, config.getSqlFragments()).parse();
            }
        }
        SqlSessionTemplate session = new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(config));
        return new Baseline(session.getMapper(CapContractMapper.class), session.getMapper(CapRuleMapper.class),
                session.getMapper(CapScheduleAmountMapper.class), session.getMapper(RefundRateMapper.class),
                session.getMapper(CapIncludedAmountMapper.class));
    }

    private Fixture insertFixture() {
        Long sourceId = jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL02-202601-0001'", Long.class);
        List<Long> ids = jdbc.queryForList("""
                INSERT INTO fgc.insurance_contract (
                    insurer_id, product_offering_id, contract_no, contract_date, agent_id, organization_id,
                    premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                    premium_conversion_rule_code, payment_cycle_code, payment_term_months,
                    standard_surrender_deduction_amount, current_status, data_origin)
                SELECT insurer_id, product_offering_id, ? || lpad(n::text, 3, '0'), contract_date, agent_id, organization_id,
                       premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                       premium_conversion_rule_code, payment_cycle_code, payment_term_months,
                       standard_surrender_deduction_amount, current_status, 'MANUAL'
                  FROM fgc.insurance_contract CROSS JOIN generate_series(1, ?) n WHERE contract_id = ?
                RETURNING contract_id
                """, Long.class, CONTRACT_PREFIX, CONTRACTS, sourceId);
        ids.sort(Long::compareTo);
        Long itemId = jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code = 'BASE_COMMISSION'", Long.class);
        Long policyId = jdbc.queryForObject("SELECT policy_version_id FROM fgc.policy_version WHERE policy_code = 'INS-CUR-2026-V1' AND status = 'ACTIVE'", Long.class);
        jdbc.update("""
                INSERT INTO fgc.schedule_header (contract_id, payment_stage, policy_version_id,
                    schedule_version_no, schedule_regime, schedule_purpose, active_yn)
                SELECT contract_id, 'INSURER_TO_GA', ?, 1, 'CURRENT', 'OPERATIONAL', true
                  FROM fgc.insurance_contract WHERE contract_no LIKE ?
                """, policyId, CONTRACT_PREFIX + "%");
        jdbc.update("""
                INSERT INTO fgc.schedule_line (schedule_header_id, line_no, installment_no, contract_month_no,
                    due_date, commission_item_id, basis_code, basis_amount, calculation_type, rate_pct, expected_amount, line_status)
                SELECT sh.schedule_header_id, n, n, n, (c.contract_date + (n - 1) * INTERVAL '1 month')::date,
                       ?, 'MONTHLY_EQUIVALENT_FIRST_PREMIUM', c.monthly_equivalent_first_premium,
                       'RATE', 25.000000, 100.50 + n, CASE WHEN n = 5 THEN 'CANCELLED' ELSE 'PLANNED' END
                  FROM fgc.schedule_header sh JOIN fgc.insurance_contract c USING (contract_id)
                  CROSS JOIN generate_series(1, 13) n WHERE c.contract_no LIKE ?
                """, itemId, CONTRACT_PREFIX + "%");
        jdbc.update("""
                INSERT INTO fgc.commission_transaction (payment_stage, source_type, source_business_key, source_contract_id,
                    recipient_agent_id, commission_item_id, settlement_month, installment_no, amount, cashflow_type, status)
                SELECT 'INSURER_TO_GA', 'GA_MANUAL_PAYMENT', ? || c.contract_id || ':' || n, c.contract_id,
                       c.agent_id, ?, date_trunc('month', c.contract_date)::date, n, 100.50 + n,
                       CASE WHEN n % 7 = 0 THEN 'DEDUCTION' ELSE 'PAYMENT' END, 'DRAFT'
                  FROM fgc.insurance_contract c CROSS JOIN generate_series(1, ?) n WHERE c.contract_no LIKE ?
                """, PAYMENT_PREFIX, itemId, TRANSACTIONS_PER_CONTRACT, CONTRACT_PREFIX + "%");
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution (commission_transaction_id, attribution_seq, attribution_scope,
                    contract_id, agent_id, schedule_line_id, attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, exclusion_type_snapshot, attribution_method, allocation_basis_snapshot, evidence_ref)
                SELECT ct.commission_transaction_id, 1, 'CONTRACT', c.contract_id, c.agent_id,
                       CASE WHEN ct.installment_no <= 2 THEN sl.schedule_line_id END,
                       (c.contract_date + ((ct.installment_no - 1) % 12) * INTERVAL '1 month')::date,
                       date_trunc('month', c.contract_date + ((ct.installment_no - 1) % 12) * INTERVAL '1 month')::date,
                       ct.amount, CASE WHEN ct.installment_no <= 2 THEN 'EXCLUDED' ELSE 'INCLUDED' END,
                       CASE WHEN ct.installment_no <= 2 THEN 'NEW_AGENT_SUPPORT' END, 'DIRECT', '{}'::jsonb,
                       CASE WHEN ct.installment_no = 1 THEN ' EVD-Z ' WHEN ct.installment_no = 2 THEN ' EVD-A ' END
                  FROM fgc.commission_transaction ct
                  JOIN fgc.insurance_contract c ON c.contract_id = ct.source_contract_id
                  JOIN fgc.schedule_header sh ON sh.contract_id = c.contract_id AND sh.payment_stage = ct.payment_stage
                  JOIN fgc.schedule_line sl ON sl.schedule_header_id = sh.schedule_header_id AND sl.line_no = 1
                 WHERE ct.source_business_key LIKE ?
                """, PAYMENT_PREFIX + "%");
        jdbc.update("""
                UPDATE fgc.commission_transaction SET status = 'CONFIRMED'
                 WHERE source_business_key LIKE ? AND installment_no < ?
                """, PAYMENT_PREFIX + "%", TRANSACTIONS_PER_CONTRACT);
        Long currentId = jdbc.queryForObject("SELECT commission_transaction_id FROM fgc.commission_transaction WHERE source_business_key = ?",
                Long.class, PAYMENT_PREFIX + ids.getFirst() + ":" + TRANSACTIONS_PER_CONTRACT);
        CapContractView c = contracts.findCapViewByContractId(ids.getFirst());
        CapRuleSetView rule = rules.findApplicableRuleSet(STAGE.name(), c.getContractDate(), c.getInsurerId(), c.getProductGroupCode(), c.getChannelCode());
        RefundRateTableView table = refunds.findApplicableTable(c.getInsurerId(), c.getProductId(), c.getPaymentTermMonths(), c.getChannelCode(), c.getContractDate());
        assertThat(ids).hasSize(CONTRACTS);
        assertThat(rule).isNotNull();
        assertThat(table).isNotNull();
        return new Fixture(List.copyOf(ids), c, currentId, rule.getCapRuleSetId(), table.getRefundRateTableId());
    }

    private void recordMetadata(Fixture fixture) throws Exception {
        metadata.put("baselineSha", BASELINE_SHA);
        metadata.put("round", ROUND);
        metadata.put("startedAt", Instant.now().toString());
        metadata.put("warmupsPerSidePerOperation", WARMUPS);
        metadata.put("samplesPerSidePerOperation", SAMPLES);
        metadata.put("firstSide", CURRENT_FIRST ? "current" : "baseline");
        metadata.put("executionOrder", "AB/BA alternating within each operation; both sides use the same DataSource and outer transaction");
        metadata.put("comparison", "all DTO fields and nulls; unordered lists recursively sorted; validation before and after timing");
        metadata.put("scope", "eight repository reads, six-query calculation input bundle, 20/50-contract confirmed-amount loops; not full calculator, HTTP or commit latency");
        metadata.put("timingCaveat", "JDBC wall time includes driver/reflection work, not server-only time. elapsed minus JDBC includes ORM, dispatch and observation overhead; not pure ORM CPU.");
        metadata.put("transaction", "clean outer writable test transaction; all fixture writes flushed before measurement; rollback after test");
        metadata.put("caches", "MyBatis STATEMENT local cache, both second-level and query caches disabled; PostgreSQL warmed; no cold-cache claim");
        metadata.put("fixture", Map.of("sourceContract", "FGC-FGL02-202601-0001", "generatedContracts", CONTRACTS,
                "scheduleLinesPerContract", 13, "cancelledScheduleMonth", 5, "transactionsPerContract", TRANSACTIONS_PER_CONTRACT,
                "confirmedPerContract", TRANSACTIONS_PER_CONTRACT - 1, "draftPerContract", 1,
                "excludedAttributionsPerContract", 2, "deductionEveryNthTransaction", 7, "paymentStage", STAGE.name()));
        metadata.put("fixtureContractIds", fixture.contractIds());
        metadata.put("tableRowsBefore", rowsBefore);
        metadata.put("tableRowsDuring", rowCounts());
        metadata.put("javaVersion", System.getProperty("java.version"));
        metadata.put("javaVm", System.getProperty("java.vm.name"));
        metadata.put("jvmArguments", ManagementFactory.getRuntimeMXBean().getInputArguments());
        metadata.put("maxHeapBytes", Runtime.getRuntime().maxMemory());
        metadata.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        metadata.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        metadata.put("springBootVersion", SpringBootVersion.getVersion());
        metadata.put("hibernateVersion", org.hibernate.Version.getVersionString());
        metadata.put("mybatisVersion", Configuration.class.getPackage().getImplementationVersion());
        metadata.put("postgresqlVersion", jdbc.queryForObject("SELECT version()", String.class));
        metadata.put("searchPath", jdbc.queryForObject("SHOW search_path", String.class));
        try (var connection = dataSource.getConnection()) {
            metadata.put("jdbcDriverVersion", connection.getMetaData().getDriverVersion());
        }
        metadata.put("currentCapSourceSnapshot", sourceSnapshot());
    }

    private Map<String, Object> sourceSnapshot() throws Exception {
        List<Path> files = new ArrayList<>();
        for (Path directory : List.of(Path.of("src/main/java/com/susukkang/fgc/cap"), Path.of("src/main/resources/mapper/cap"))) {
            if (Files.exists(directory)) {
                try (var paths = Files.walk(directory)) { files.addAll(paths.filter(Files::isRegularFile).toList()); }
            }
        }
        files.sort(Comparator.comparing(Path::toString));
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Map<String, String> hashes = new LinkedHashMap<>();
        for (Path file : files) {
            String path = file.toString().replace('\\', '/');
            byte[] bytes = Files.readAllBytes(file);
            hashes.put(path, HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
            digest.update(path.getBytes(StandardCharsets.UTF_8)); digest.update((byte) 0); digest.update(bytes); digest.update((byte) 0);
        }
        return Map.of("kind", "cap production source tree SHA-256, includes untracked repositories; not Git diff hash",
                "sha256", HexFormat.of().formatHex(digest.digest()), "files", hashes);
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        FIXTURE_TABLES.forEach(table -> counts.put(table, jdbc.queryForObject("SELECT count(*) FROM fgc." + table, Long.class)));
        return counts;
    }

    private void analyzeFixtureTables() {
        FIXTURE_TABLES.forEach(table -> jdbc.execute("ANALYZE fgc." + table));
    }

    @AfterTransaction
    void verifiesRollbackAndRestoresPlannerStatistics() throws IOException {
        if (rowsBefore == null) return;
        Map<String, Long> after = rowCounts();
        metadata.put("tableRowsAfterRollback", after);
        metadata.put("rollbackVerified", rowsBefore.equals(after));
        analyzeFixtureTables();
        writeReports();
        assertThat(after).as("all generated fixture rows rolled back").isEqualTo(rowsBefore);
    }

    private void writeReports() throws IOException {
        Path directory = Path.of("build/reports/cap-performance");
        Files.createDirectories(directory);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("metadata", metadata);
        report.put("summaries", summaries());
        report.put("samples", samples);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(ROUND + ".json").toFile(), report);
        StringBuilder csv = new StringBuilder("operation,side,pair,order,elapsed_ns,jdbc_ns,prepare_ns,execute_ns,result_set_ns,other_jdbc_ns,sql_count,dml_count,entity_loads,entity_writes\n");
        for (Sample s : samples) {
            csv.append(String.format(Locale.ROOT, "%s,%s,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    s.operation(), s.side(), s.pair(), s.order(), s.elapsedNanos(), s.jdbcNanos(), s.prepareNanos(), s.executeNanos(),
                    s.resultSetNanos(), s.otherJdbcNanos(), s.sqlCount(), s.dmlCount(), s.entityLoads(), s.entityWrites()));
        }
        Files.writeString(directory.resolve(ROUND + ".csv"), csv, StandardCharsets.UTF_8);
        StringBuilder sql = new StringBuilder("Baseline SHA: " + BASELINE_SHA + "\nOriginal JDBC SQL; counts are recorded for every sample in CSV/JSON.\n");
        rawSql.forEach((name, statements) -> { sql.append("\n=== ").append(name).append(" ===\n");
            statements.forEach(statement -> sql.append(statement).append(";\n\n")); });
        Files.writeString(directory.resolve(ROUND + ".sql.txt"), sql, StandardCharsets.UTF_8);
    }

    private List<Map<String, Object>> summaries() {
        Map<String, List<Sample>> groups = new LinkedHashMap<>();
        samples.forEach(s -> groups.computeIfAbsent(s.operation() + "/" + s.side(), key -> new ArrayList<>()).add(s));
        List<Map<String, Object>> result = new ArrayList<>();
        groups.forEach((name, group) -> {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("operation", group.getFirst().operation()); summary.put("side", group.getFirst().side());
            summary.put("samples", group.size());
            summary.put("elapsedP50Ms", percentile(group, Sample::elapsedNanos, .50));
            summary.put("elapsedP95Ms", percentile(group, Sample::elapsedNanos, .95));
            summary.put("jdbcP50Ms", percentile(group, Sample::jdbcNanos, .50));
            summary.put("jdbcP95Ms", percentile(group, Sample::jdbcNanos, .95));
            summary.put("nonJdbcAndObservationP50Ms", percentile(group, s -> s.elapsedNanos() - s.jdbcNanos(), .50));
            summary.put("sqlCountMin", group.stream().mapToInt(Sample::sqlCount).min().orElse(0));
            summary.put("sqlCountMax", group.stream().mapToInt(Sample::sqlCount).max().orElse(0));
            summary.put("entityLoads", group.stream().mapToLong(Sample::entityLoads).sum());
            summary.put("entityWrites", group.stream().mapToLong(Sample::entityWrites).sum());
            summary.put("dmlCount", group.stream().mapToLong(Sample::dmlCount).sum());
            result.add(summary);
        });
        return result;
    }

    private double percentile(List<Sample> group, ToLongFunction<Sample> metric, double fraction) {
        long[] values = group.stream().mapToLong(metric).sorted().toArray();
        return values[(int) Math.ceil(values.length * fraction) - 1] / 1_000_000.0;
    }

    private record Baseline(CapContractMapper contracts, CapRuleMapper rules, CapScheduleAmountMapper schedules,
                            RefundRateMapper refunds, CapIncludedAmountMapper included) { }
    private record Fixture(List<Long> contractIds, CapContractView contract, Long currentPaymentId,
                           Long ruleId, Long refundTableId) { }
    private record Benchmark(String name, int expectedSqlCount, Supplier<?> baseline, Supplier<?> current) { }
    private record InputBundle(CapContractView contract, CapRuleSetView rule, List<CapRuleItemView> items,
                               List<ScheduleAmountView> schedule, RefundRateTableView refundTable, BigDecimal month12Rate) { }
    private record Sample(String operation, String side, int pair, int order, long elapsedNanos, long jdbcNanos,
                          long prepareNanos, long executeNanos, long resultSetNanos, long otherJdbcNanos,
                          int sqlCount, long dmlCount, long entityLoads, long entityWrites) { }
}
