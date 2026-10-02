package com.susukkang.fgc.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.*;
import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.transaction.dto.*;
import com.susukkang.fgc.transaction.performance.PaymentJdbcObservation;
import com.susukkang.fgc.transaction.performance.PaymentJdbcObservation.Snapshot;
import com.susukkang.fgc.transaction.performance.baseline.PaymentBaselineConfiguration;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 동일 JVM·DB에서 d1a603df 서비스/Mapper 복원본과 현재 JPA 서비스를 교대 비교한다.
 * 각 lifecycle은 별도 롤백 트랜잭션이며 커밋·HTTP·검증 시간은 제외한다.
 * JDBC 시간은 드라이버 호출의 경과시간이며 DB 서버 실행시간이나 순수 ORM 비용이 아니다.
 *
 * @author hjKang
 * @since 2026-09-30
 * @version 2.0
 */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.jpa.properties.hibernate.cache.use_query_cache=false",
        "spring.jpa.properties.hibernate.cache.use_second_level_cache=false",
        "mybatis.configuration.local-cache-scope=STATEMENT",
        "mybatis.configuration.cache-enabled=false",
        "logging.level.org.hibernate.SQL=OFF"
})
@Import({PaymentJdbcObservation.Config.class, PaymentBaselineConfiguration.class})
@WithMockUser(roles = "SETTLEMENT")
@EnabledIfSystemProperty(named = "fgc.payment.performance", matches = "true")
class CommissionPaymentPersistencePerformanceTest {
    private static final int WARMUPS = Integer.getInteger("fgc.payment.performance.warmups", 40);
    private static final int SAMPLES = Integer.getInteger("fgc.payment.performance.samples", 120);
    private static final String RUN = System.getProperty("fgc.payment.performance.run", "comparison");
    private static final boolean JPA_FIRST = Boolean.getBoolean("fgc.payment.performance.jpaFirst");
    private static final List<String> OPERATIONS = List.of("create", "update", "precheck", "confirm", "retry");
    private static final Set<String> GENERATED_FIELDS = Set.of(
            "paymentId", "commissionTransactionId", "capCheckId", "capCheckIds", "transactionAttributionId",
            "createdAt", "updatedAt", "checkedAt",
            "commission_transaction_id", "candidate_transaction_id", "transaction_attribution_id",
            "cap_check_id", "cap_check_detail_id", "confirm_cap_check_ids",
            "created_at", "updated_at", "checked_at", "audit_log_id", "occurred_at", "entity_id", "request_id");
    private static final List<String> TABLES = List.of(
            "commission_transaction", "transaction_attribution", "cap_check", "cap_check_detail",
            "exception_case", "audit_log");

    @Autowired @Qualifier("commissionPaymentServiceImpl") private CommissionPaymentService payments;
    @Autowired @Qualifier("baselineCommissionPaymentService") private CommissionPaymentService baseline;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private ObjectMapper objectMapper;
    private final List<Sample> samples = new ArrayList<>();

    @Test
    void measuresSingleAndMultipleContractPaymentLifecycles() throws IOException {
        assertThat(WARMUPS).isGreaterThanOrEqualTo(0);
        assertThat(SAMPLES).isGreaterThanOrEqualTo(5);
        assertThat(RUN).matches("[A-Za-z0-9_-]+");
        assertThat(AopUtils.isAopProxy(payments)).isTrue();
        assertThat(AopUtils.isAopProxy(baseline)).isTrue();
        Map<String, Long> before = rowCounts();
        Long secondContract = jdbc.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?", Long.class,
                "FGC-FGL01-202607-0002");
        Long itemId = jdbc.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item WHERE item_code = 'BASE_COMMISSION'", Long.class);
        System.out.printf(Locale.ROOT,
                "PAYMENT_AB_CONFIG run=%s baseline=d1a603df warmups=%d samples=%d jpa_first=%s java=%s max_heap_mb=%d%n",
                RUN, WARMUPS, SAMPLES, JPA_FIRST, System.getProperty("java.version"),
                Runtime.getRuntime().maxMemory() / 1024 / 1024);
        try {
            for (List<Long> contracts : List.of(List.of(1L), List.of(1L, secondContract))) {
                for (int index = -WARMUPS; index < SAMPLES; index++) {
                    boolean record = index >= 0;
                    boolean firstJpa = (Math.floorMod(index, 2) == 0) == JPA_FIRST;
                    String key = "PERF-373-" + UUID.randomUUID();
                    JsonNode oldResult;
                    JsonNode newResult;
                    if (firstJpa) {
                        newResult = lifecycle(payments, "jpa", contracts, itemId, key, index, 0, record);
                        oldResult = lifecycle(baseline, "mybatis", contracts, itemId, key, index, 1, record);
                    } else {
                        oldResult = lifecycle(baseline, "mybatis", contracts, itemId, key, index, 0, record);
                        newResult = lifecycle(payments, "jpa", contracts, itemId, key, index, 1, record);
                    }
                    assertThat(newResult).as("normalized response and DB equivalence, contracts=%s pair=%s",
                            contracts.size(), index).isEqualTo(oldResult);
                    if (record && (index + 1) % 30 == 0) {
                        System.out.printf("PAYMENT_AB_PROGRESS run=%s contracts=%d pairs=%d/%d%n",
                                RUN, contracts.size(), index + 1, SAMPLES);
                    }
                }
            }
            report();
        } finally {
            writeCsv();
            assertThat(rowCounts()).as("all lifecycle writes rolled back").isEqualTo(before);
            System.out.println("PAYMENT_AB_CLEANUP rollback_verified=true table_counts_unchanged=true");
        }
    }

    private JsonNode lifecycle(CommissionPaymentService service, String implementation, List<Long> contracts,
                               Long itemId, String key, int pair, int order, boolean record) {
        return new TransactionTemplate(transactionManager).execute(tx -> {
            try {
                List<CommissionPaymentAttributionRequest> attributions = contracts.stream().map(id ->
                        new CommissionPaymentAttributionRequest(id, LocalDate.of(2026, 7, 31), BigDecimal.TEN,
                                InclusionDecisionStatus.INCLUDED, ExclusionType.NONE, "지급 성능 검증",
                                "DIRECT", "PERF-EVIDENCE", AttributionMethod.DIRECT)).toList();
                CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                        "GA_MANUAL_PAYMENT", key, 1L, 6L, itemId,
                        BigDecimal.TEN.multiply(BigDecimal.valueOf(contracts.size())),
                        LocalDate.of(2026, 7, 1), "PAYMENT", LocalDate.of(2026, 7, 31),
                        PaymentStage.GA_TO_FC, 4L, attributions, "PERF-EVIDENCE", "issue 373");
                CommissionPaymentResponse created = measure(implementation, contracts.size(), "create", pair, order,
                        record, () -> service.create(request));
                assertThat(created.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
                assertThat(created.paymentId()).isPositive();
                CommissionPaymentUpdateRequest update = new CommissionPaymentUpdateRequest(
                        request.sourceType(), key, 1L, 6L, itemId, request.amount(), request.settlementMonth(),
                        request.cashflowType(), request.scheduledPaymentDate(), request.paymentStage(),
                        4L, attributions, "PERF-EVIDENCE", "updated");
                CommissionPaymentResponse updated = measure(implementation, contracts.size(), "update", pair, order,
                        record, () -> service.update(created.paymentId(), update));
                TransactionPrecheckResponse preview = measure(implementation, contracts.size(), "precheck", pair,
                        order, record, () -> service.precheck(created.paymentId()));
                assertThat(preview.confirmable()).isTrue();
                assertThat(preview.blockers()).isEmpty();
                assertThat(preview.capPreview()).hasSize(contracts.size())
                        .allSatisfy(row -> assertThat(row.capCheckId()).isNull());
                CommissionPaymentResponse confirmed = measure(implementation, contracts.size(), "confirm", pair,
                        order, record, () -> service.confirm(created.paymentId(), key));
                CommissionPaymentResponse retried = measure(implementation, contracts.size(), "retry", pair, order,
                        record, () -> service.confirm(created.paymentId(), key));
                assertThat(confirmed.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
                assertThat(confirmed.attributions()).hasSize(contracts.size());
                assertThat(confirmed.capCheckIds()).hasSize(contracts.size()).doesNotHaveDuplicates();
                assertThat(retried.capCheckIds()).containsExactlyElementsOf(confirmed.capCheckIds());
                assertThat(created.createdAt()).isNotNull();
                assertThat(updated.updatedAt()).isNotNull();

                // 검증 SQL과 JSON 정규화는 모든 서비스 측정이 끝난 뒤 실행한다.
                ObjectNode result = objectMapper.createObjectNode();
                result.set("create", normalized(objectMapper.valueToTree(created)));
                result.set("update", normalized(objectMapper.valueToTree(updated)));
                result.set("precheck", normalized(objectMapper.valueToTree(preview)));
                result.set("confirm", normalized(objectMapper.valueToTree(confirmed)));
                result.set("retry", normalized(objectMapper.valueToTree(retried)));
                result.set("database", databaseSnapshot(created.paymentId(), contracts.size(), confirmed.capCheckIds()));
                return result;
            } finally {
                tx.setRollbackOnly();
            }
        });
    }

    private JsonNode databaseSnapshot(Long paymentId, int contracts, List<Long> capIds) {
        ObjectNode data = objectMapper.createObjectNode();
        data.set("payment", rows("SELECT to_jsonb(t)::text FROM fgc.commission_transaction t"
                + " WHERE commission_transaction_id = ?", paymentId));
        data.set("attributions", rows("SELECT to_jsonb(t)::text FROM fgc.transaction_attribution t"
                + " WHERE commission_transaction_id = ? ORDER BY attribution_seq", paymentId));
        data.set("capChecks", rows("SELECT to_jsonb(t)::text FROM fgc.cap_check t"
                + " WHERE candidate_transaction_id = ? ORDER BY contract_id, payment_stage", paymentId));
        data.set("details", rows("SELECT to_jsonb(d)::text FROM fgc.cap_check_detail d"
                + " JOIN fgc.cap_check c USING(cap_check_id)"
                + " WHERE c.candidate_transaction_id = ? ORDER BY c.contract_id, d.detail_seq", paymentId));
        data.set("audit", rows("SELECT to_jsonb(a)::text FROM fgc.audit_log a"
                + " WHERE entity_type = 'COMMISSION_PAYMENT' AND entity_id = ? ORDER BY audit_log_id",
                paymentId.toString()));
        assertThat(data.get("payment").size()).isEqualTo(1);
        assertThat(data.get("attributions").size()).isEqualTo(contracts);
        assertThat(data.get("capChecks").size()).isEqualTo(contracts);
        assertThat(data.get("details").size()).isGreaterThanOrEqualTo(contracts);
        assertThat(data.get("audit").size()).isEqualTo(3);
        assertThat(jdbc.queryForList("SELECT request_id FROM fgc.audit_log"
                + " WHERE entity_type='COMMISSION_PAYMENT' AND entity_id=?", String.class, paymentId.toString()))
                .hasSize(3).allSatisfy(requestId -> assertThat(requestId).isNotBlank());
        assertThat(jdbc.queryForList("SELECT cap_check_id FROM fgc.cap_check"
                + " WHERE candidate_transaction_id = ? ORDER BY cap_check_id", Long.class, paymentId))
                .containsExactlyInAnyOrderElementsOf(capIds);
        data.set("exceptions", rows("""
                SELECT jsonb_build_object(
                    'type', e.exception_type, 'reason', e.reason_code, 'severity', e.severity,
                    'status', e.status, 'contractId', e.contract_id, 'agentId', e.agent_id,
                    'policyVersionId', e.policy_version_id, 'validationRunId', e.validation_run_id,
                    'validationMonth', e.validation_month, 'sourceType', e.source_entity_type,
                    'key', replace(e.exception_key, ':COMMISSION_TRANSACTION:' || e.source_entity_id || ':',
                                   ':COMMISSION_TRANSACTION:<payment>:'),
                    'title', e.title, 'description', e.description,
                    'capCheckLinked', EXISTS(SELECT 1 FROM fgc.cap_check c
                          WHERE c.cap_check_id=e.cap_check_id
                            AND CAST(c.candidate_transaction_id AS varchar)=e.source_entity_id)
                )::text FROM fgc.exception_case e
                 WHERE e.source_entity_type='COMMISSION_TRANSACTION' AND e.source_entity_id=?
                 ORDER BY e.contract_id, e.exception_type
                """, paymentId.toString()));
        assertThat(data.get("exceptions").size()).isEqualTo(contracts - 1);
        data.get("exceptions").forEach(exception -> {
            assertThat(exception.get("type").asText()).isEqualTo("CAP_WARNING");
            assertThat(exception.get("capCheckLinked").asBoolean()).isTrue();
        });
        return data;
    }

    private ArrayNode rows(String sql, Object parameter) {
        ArrayNode array = objectMapper.createArrayNode();
        for (String json : jdbc.queryForList(sql, String.class, parameter)) {
            try { array.add(normalized(objectMapper.readTree(json))); }
            catch (IOException failure) { throw new IllegalStateException(failure); }
        }
        return array;
    }

    private JsonNode normalized(JsonNode node) {
        if (node.isObject()) {
            ObjectNode result = objectMapper.createObjectNode();
            node.fields().forEachRemaining(entry -> {
                if (!GENERATED_FIELDS.contains(entry.getKey())) result.set(entry.getKey(), normalized(entry.getValue()));
            });
            return result;
        }
        if (node.isArray()) {
            ArrayNode result = objectMapper.createArrayNode();
            node.forEach(value -> result.add(normalized(value)));
            return result;
        }
        if (node.isNumber()) return DecimalNode.valueOf(node.decimalValue().stripTrailingZeros());
        return node;
    }

    private Map<String, Long> rowCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String table : TABLES)
            counts.put(table, jdbc.queryForObject("SELECT count(*) FROM fgc." + table, Long.class));
        return counts;
    }

    private <T> T measure(String implementation, int contracts, String operation, int pair, int order,
                          boolean record, Supplier<T> action) {
        VmSnapshot before = VmSnapshot.capture();
        PaymentJdbcObservation.start();
        long epochMillis = System.currentTimeMillis();
        long started = System.nanoTime();
        T response;
        long elapsed;
        Snapshot observation;
        try {
            response = action.get();
            elapsed = System.nanoTime() - started;
        } finally {
            observation = PaymentJdbcObservation.stop();
        }
        VmSnapshot after = VmSnapshot.capture();
        if (record) {
            samples.add(new Sample(implementation, contracts, operation, pair, order, epochMillis, elapsed,
                    observation, after.cpuNanos - before.cpuNanos, after.gcCount - before.gcCount,
                    after.gcMillis - before.gcMillis, after.jitMillis - before.jitMillis));
        }
        return response;
    }

    private void report() {
        for (int contracts : List.of(1, 2)) {
            for (String operation : OPERATIONS) {
                for (String implementation : List.of("mybatis", "jpa")) {
                    List<Sample> group = group(contracts, operation, implementation);
                    assertThat(group).hasSize(SAMPLES);
                    assertThat(group.stream().map(s -> s.jdbc.executions().size()).distinct()).hasSize(1);
                    assertThat(group.stream().flatMap(s -> s.jdbc.executions().stream())
                            .anyMatch(s -> s.method().contains("Batch"))).isFalse();
                    System.out.printf(Locale.ROOT,
                            "PAYMENT_AB run=%s contracts=%d operation=%s impl=%s n=%d sql=%d"
                            + " p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f max_ms=%.3f"
                            + " jdbc_p50_ms=%.3f execute_p50_ms=%.3f outside_jdbc_p50_ms=%.3f"
                            + " cpu_p50_ms=%.3f gc_affected=%d jit_affected=%d%n",
                            RUN, contracts, operation, implementation, group.size(),
                            group.getFirst().jdbc.executions().size(),
                            percentile(group, Sample::nanos, .50), percentile(group, Sample::nanos, .95),
                            percentile(group, Sample::nanos, .99), percentile(group, Sample::nanos, 1),
                            percentile(group, s -> jdbcNanos(s.jdbc), .50),
                            percentile(group, s -> s.jdbc.executeNanos(), .50),
                            percentile(group, s -> s.nanos - jdbcNanos(s.jdbc), .50),
                            percentile(group, Sample::cpuNanos, .50),
                            group.stream().filter(s -> s.gcCount > 0).count(),
                            group.stream().filter(s -> s.jitMillis > 0).count());
                    group.stream().sorted(Comparator.comparingLong(Sample::nanos).reversed()).limit(3).forEach(s -> {
                        var slowest = s.jdbc.executions().stream()
                                .max(Comparator.comparingLong(PaymentJdbcObservation.SqlExecution::nanos)).orElseThrow();
                        System.out.printf(Locale.ROOT,
                                "PAYMENT_AB_OUTLIER run=%s contracts=%d operation=%s impl=%s pair=%d order=%d"
                                + " epoch_ms=%d total_ms=%.3f jdbc_ms=%.3f execute_ms=%.3f prepare_ms=%.3f"
                                + " resultset_ms=%.3f other_jdbc_ms=%.3f outside_jdbc_ms=%.3f"
                                + " cpu_ms=%.3f gc_count=%d gc_ms=%d jit_ms=%d slowest_execute_ms=%.3f sql=%s%n",
                                RUN, contracts, operation, implementation, s.pair, s.order, s.epochMillis,
                                ms(s.nanos), ms(jdbcNanos(s.jdbc)), ms(s.jdbc.executeNanos()), ms(s.jdbc.prepareNanos()),
                                ms(s.jdbc.resultSetNanos()), ms(s.jdbc.otherJdbcNanos()),
                                ms(s.nanos - jdbcNanos(s.jdbc)), ms(s.cpuNanos), s.gcCount, s.gcMillis, s.jitMillis,
                                ms(slowest.nanos()), slowest.sql().replaceAll("\\s+", " ").trim());
                    });
                }
                List<Sample> old = group(contracts, operation, "mybatis");
                List<Sample> current = group(contracts, operation, "jpa");
                long[] differences = new long[SAMPLES];
                for (int i = 0; i < SAMPLES; i++) {
                    assertThat(old.get(i).pair).isEqualTo(current.get(i).pair);
                    differences[i] = current.get(i).nanos - old.get(i).nanos;
                }
                Arrays.sort(differences);
                System.out.printf(Locale.ROOT,
                        "PAYMENT_AB_PAIRED run=%s contracts=%d operation=%s median_jpa_minus_mybatis_ms=%.3f%n",
                        RUN, contracts, operation, ms(differences[(SAMPLES - 1) / 2]));
            }
        }
    }

    private List<Sample> group(int contracts, String operation, String implementation) {
        return samples.stream().filter(s -> s.contracts == contracts && s.operation.equals(operation)
                && s.implementation.equals(implementation)).sorted(Comparator.comparingInt(Sample::pair)).toList();
    }

    private double percentile(List<Sample> values, ToLongFunction<Sample> field, double percentile) {
        long[] sorted = values.stream().mapToLong(field).sorted().toArray();
        return ms(sorted[(int) Math.ceil(sorted.length * percentile) - 1]);
    }

    private void writeCsv() throws IOException {
        Path output = Path.of("build", "reports", "payment-performance", RUN + ".csv");
        Files.createDirectories(output.getParent());
        List<String> lines = new ArrayList<>();
        lines.add("run,contracts,operation,implementation,pair,order,epoch_ms,total_ms,sql,prepare_ms,execute_ms,"
                + "resultset_ms,other_jdbc_ms,outside_jdbc_ms,cpu_ms,gc_count,gc_ms,jit_ms");
        for (Sample s : samples)
            lines.add(String.format(Locale.ROOT,
                    "%s,%d,%s,%s,%d,%d,%d,%.6f,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,%d",
                    RUN, s.contracts, s.operation, s.implementation, s.pair, s.order, s.epochMillis,
                    ms(s.nanos), s.jdbc.executions().size(), ms(s.jdbc.prepareNanos()), ms(s.jdbc.executeNanos()),
                    ms(s.jdbc.resultSetNanos()), ms(s.jdbc.otherJdbcNanos()), ms(s.nanos - jdbcNanos(s.jdbc)),
                    ms(s.cpuNanos), s.gcCount, s.gcMillis, s.jitMillis));
        Files.write(output, lines, StandardCharsets.UTF_8);
        System.out.println("PAYMENT_AB_SAMPLES path=" + output + " rows=" + samples.size());
    }

    private static long jdbcNanos(Snapshot jdbc) {
        return jdbc.prepareNanos() + jdbc.executeNanos() + jdbc.resultSetNanos() + jdbc.otherJdbcNanos();
    }

    private static double ms(long nanos) { return nanos / 1_000_000.0; }

    private record Sample(String implementation, int contracts, String operation, int pair, int order,
                          long epochMillis, long nanos, Snapshot jdbc, long cpuNanos,
                          long gcCount, long gcMillis, long jitMillis) { }

    private record VmSnapshot(long cpuNanos, long gcCount, long gcMillis, long jitMillis) {
        static VmSnapshot capture() {
            long count = 0, millis = 0;
            for (var gc : ManagementFactory.getGarbageCollectorMXBeans()) {
                count += Math.max(0, gc.getCollectionCount());
                millis += Math.max(0, gc.getCollectionTime());
            }
            var thread = ManagementFactory.getThreadMXBean();
            var compiler = ManagementFactory.getCompilationMXBean();
            return new VmSnapshot(thread.isCurrentThreadCpuTimeSupported() ? thread.getCurrentThreadCpuTime() : 0,
                    count, millis, compiler != null && compiler.isCompilationTimeMonitoringSupported()
                    ? compiler.getTotalCompilationTime() : 0);
        }
    }
}
