package com.susukkang.fgc.transaction;

import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.transaction.dto.*;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 동일 시드·요청에서 지급 등록/수정/확정 SQL 수와 시간을 측정한다.
 * 이전 커밋에도 같은 테스트를 복사해 실행할 수 있도록 공개 서비스 API만 사용한다.
 * 측정마다 전체 트랜잭션을 롤백하며 커밋·네트워크 응답 시간은 포함하지 않는다.
 *
 * @author Codex
 * @since 2026-09-30
 * @version 1.0
 */
@SpringBootTest(properties = "fgc.batch.daily-changed-contract.enabled=false")
@Import(CommissionPaymentPersistencePerformanceTest.ObservationConfig.class)
@WithMockUser(roles = "SETTLEMENT")
@EnabledIfSystemProperty(named = "fgc.payment.performance", matches = "true")
class CommissionPaymentPersistencePerformanceTest {
    private static final int WARMUPS = 3;
    private static final int SAMPLES = 10;
    private static final ThreadLocal<List<String>> OBSERVED_SQL = new ThreadLocal<>();
    @Autowired private CommissionPaymentService payments;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    void measuresSingleAndMultipleContractPaymentLifecycles() {
        // CommissionPaymentIntegrationTest와 같은 유효한 지급·귀속 조합을 사용한다.
        Long firstContract = 1L;
        Long secondContract = contractId("FGC-FGL01-202607-0002");
        Long itemId = jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code = 'BASE_COMMISSION'", Long.class);
        Long agentId = 6L;
        Long policyId = 4L;
        for (List<Long> contracts : List.of(List.of(firstContract), List.of(firstContract, secondContract))) {
            Map<String, List<Sample>> samples = new LinkedHashMap<>();
            for (int iteration = -WARMUPS; iteration < SAMPLES; iteration++) {
                boolean record = iteration >= 0;
                new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                    String key = "PERF-373-" + UUID.randomUUID();
                    List<CommissionPaymentAttributionRequest> attributions = contracts.stream().map(id ->
                            new CommissionPaymentAttributionRequest(id, LocalDate.of(2026, 7, 31),
                                    new BigDecimal("10"), InclusionDecisionStatus.INCLUDED, ExclusionType.NONE,
                                    "지급 성능 검증", "DIRECT", "PERF-EVIDENCE", AttributionMethod.DIRECT)).toList();
                    CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                            "GA_MANUAL_PAYMENT", key, firstContract, agentId, itemId,
                            BigDecimal.TEN.multiply(BigDecimal.valueOf(contracts.size())),
                            LocalDate.of(2026, 7, 1), "PAYMENT", LocalDate.of(2026, 7, 31),
                            PaymentStage.GA_TO_FC, policyId, attributions, "PERF-EVIDENCE", "issue 373");
                    CommissionPaymentResponse created = measure("create", record, samples, () -> payments.create(request));
                    CommissionPaymentUpdateRequest update = new CommissionPaymentUpdateRequest(
                            request.sourceType(), key, firstContract, agentId, itemId, request.amount(),
                            request.settlementMonth(), request.cashflowType(), request.scheduledPaymentDate(),
                            request.paymentStage(), policyId, attributions, "PERF-EVIDENCE", "updated");
                    measure("update", record, samples, () -> payments.update(created.paymentId(), update));
                    assertThat(measure("precheck", record, samples, () -> payments.precheck(created.paymentId())).confirmable()).isTrue();
                    CommissionPaymentResponse confirmed = measure("confirm", record, samples,
                            () -> payments.confirm(created.paymentId(), key));
                    CommissionPaymentResponse retried = measure("retry", record, samples,
                            () -> payments.confirm(created.paymentId(), key));
                    assertThat(confirmed.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
                    assertThat(confirmed.attributions()).hasSize(contracts.size());
                    assertThat(confirmed.capCheckIds()).hasSize(contracts.size());
                    assertThat(retried.capCheckIds()).containsExactlyElementsOf(confirmed.capCheckIds());
                    tx.setRollbackOnly();
                });
            }
            samples.forEach((operation, values) -> {
                assertThat(values).hasSize(SAMPLES);
                assertThat(values.stream().map(s -> s.sql().size()).distinct().count()).isEqualTo(1);
                long[] elapsed = values.stream().mapToLong(Sample::nanos).sorted().toArray();
                System.out.printf(Locale.ROOT,
                        "PAYMENT_PERF contracts=%d operation=%s sql=%d p50_ms=%.3f p95_ms=%.3f samples=%d%n",
                        contracts.size(), operation, values.getFirst().sql().size(), elapsed[4] / 1_000_000.0,
                        elapsed[9] / 1_000_000.0, SAMPLES);
                System.out.println("PAYMENT_SQL contracts=" + contracts.size() + " operation=" + operation + " " + values.getFirst().sql());
            });
        }
    }

    private Long contractId(String number) {
        return jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?", Long.class, number);
    }

    private <T> T measure(String operation, boolean record, Map<String, List<Sample>> samples, Supplier<T> action) {
        List<String> statements = new ArrayList<>();
        OBSERVED_SQL.set(statements);
        long started = System.nanoTime();
        try {
            T response = action.get();
            long nanos = System.nanoTime() - started;
            if (record) samples.computeIfAbsent(operation, ignored -> new ArrayList<>()).add(new Sample(nanos, statements));
            return response;
        } finally {
            OBSERVED_SQL.remove();
        }
    }

    private record Sample(long nanos, List<String> sql) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfig {
        @Bean
        static BeanPostProcessor observedPaymentDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                    if (!(bean instanceof DataSource source)) return bean;
                    return new DelegatingDataSource(source) {
                        @Override public Connection getConnection() throws SQLException { return observe(super.getConnection()); }
                        @Override public Connection getConnection(String user, String password) throws SQLException {
                            return observe(super.getConnection(user, password));
                        }
                    };
                }
            };
        }
    }

    private static Connection observe(Connection connection) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(connection, args);
                        if (result instanceof Statement statement && List.of("prepareStatement", "prepareCall", "createStatement").contains(method.getName())) {
                            String sql = args != null && args.length > 0 && args[0] instanceof String text ? text : null;
                            Class<?> type = statement instanceof CallableStatement ? CallableStatement.class
                                    : statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, call, arguments) -> {
                                List<String> observed = OBSERVED_SQL.get();
                                if (observed != null && List.of("execute", "executeQuery", "executeUpdate", "executeLargeUpdate", "executeBatch", "executeLargeBatch").contains(call.getName())) {
                                    String executed = arguments != null && arguments.length > 0 && arguments[0] instanceof String text ? text : sql;
                                    if (executed == null) throw new IllegalStateException("Unobservable SQL: " + call.getName());
                                    observed.add(executed.replaceAll("\\s+", " ").trim());
                                }
                                try { return call.invoke(statement, arguments); }
                                catch (InvocationTargetException failure) { throw failure.getCause(); }
                            });
                        }
                        return result;
                    } catch (InvocationTargetException failure) { throw failure.getCause(); }
                });
    }
}
