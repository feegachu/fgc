package com.susukkang.fgc.contract.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractView;
import com.susukkang.fgc.contract.service.ContractService;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.test.context.transaction.AfterTransaction;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 동일 DB·입력에서 기존 MyBatis SQL과 현재 조회 저장소를 비교하는 명시적 실행용 측정.
 * 워밍업 후 교차 실행하며 시간은 합격 기준으로 사용하지 않는다.
 * 픽스처는 테스트 트랜잭션 종료 시 모두 롤백한다.
 */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.stat=OFF",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
@Import(ContractReadPerformanceTest.SqlObservationConfig.class)
@Transactional
@EnabledIfSystemProperty(named = "fgc.contract.performance", matches = "true")
class ContractReadPerformanceTest {
    private static final int WARMUPS = 30;
    private static final int SAMPLES = 100;
    private static final ThreadLocal<List<String>> EXECUTED_SQL = new ThreadLocal<>();

    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ContractService contractService;
    @Autowired private InsuranceContractRepository contracts;
    @Autowired private ContractTransactionProjectionRepository transactions;
    @Autowired private ContractJournalProjectionRepository journals;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void compareLegacyQueriesAndCurrentRepositories() throws Exception {
        Long contractId = jdbc.queryForObject("""
                SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL01-202607-0001'
                """, Long.class);
        addRepresentativeRows(contractId);
        SqlSessionTemplate baseline = baselineSession();
        ContractSearchCondition all = new ContractSearchCondition();
        ContractSearchCondition filtered = new ContractSearchCondition();
        filtered.setContractNo("FGC-FGL01");
        filtered.setInsurerId(jdbc.queryForObject(
                "SELECT insurer_id FROM fgc.insurance_contract WHERE contract_id = ?", Long.class, contractId));
        Map<String, Object> byId = Map.of("contractId", contractId, "id", contractId);

        System.out.println("CONTRACT_PERF_DATA contracts=" + jdbc.queryForObject(
                "SELECT count(*) FROM fgc.insurance_contract", Long.class)
                + " attributionRows=" + jdbc.queryForObject(
                "SELECT count(*) FROM fgc.transaction_attribution WHERE contract_id = ?", Long.class, contractId)
                + " journalLines=" + jdbc.queryForObject("""
                SELECT count(*) FROM fgc.journal_line l JOIN fgc.journal_header h USING(journal_header_id)
                 WHERE h.contract_id = ?
                """, Long.class, contractId));

        compare("list-page-1", 2, () -> legacyPage(baseline, all, 1),
                () -> contractService.selectByCondition(all, 1, 20));
        compare("list-page-125", 2, () -> legacyPage(baseline, all, 125),
                () -> contractService.selectByCondition(all, 125, 20));
        compare("list-filtered", 2, () -> legacyPage(baseline, filtered, 1),
                () -> contractService.selectByCondition(filtered, 1, 20));
        compare("detail", 1, () -> baseline.selectOne("contractBaseline.selectContractDetailById", byId),
                () -> contractService.selectContractDetailById(contractId));
        compare("transactions", 3, () -> {
            baseline.selectOne("contractBaseline.selectContractById", byId);
            return List.of(baseline.selectList("contractBaseline.findTransactionAttributionsByContractId", byId),
                    baseline.selectList("contractBaseline.findReconciliationResultsByContractId", byId));
        }, () -> {
            contracts.existsById(contractId);
            return List.of(transactions.findTransactionAttributionsByContractId(contractId),
                    transactions.findReconciliationResultsByContractId(contractId));
        });
        compare("journals", 2, () -> {
            baseline.selectOne("contractBaseline.selectContractById", byId);
            return baseline.selectList("contractBaseline.findJournalLinesByContractId", byId);
        }, () -> {
            contracts.existsById(contractId);
            return journals.findJournalLinesByContractId(contractId);
        });
        compare("csv-all", 1, () -> baseline.selectList("contractBaseline.selectAllByCondition", Map.of("condition", all)),
                () -> contractService.selectAllByCondition(all));
    }

    private PageResponse<ContractView> legacyPage(SqlSessionTemplate session, ContractSearchCondition condition, int page) {
        List<ContractView> content = session.selectList("contractBaseline.selectByCondition",
                Map.of("condition", condition, "size", 20, "offset", (page - 1) * 20));
        long count = session.selectOne("contractBaseline.countByCondition", condition);
        return PageResponse.of(content, page, 20, count, "contractId,desc");
    }

    private SqlSessionTemplate baselineSession() throws Exception {
        Configuration configuration = new Configuration(new Environment("contract-baseline",
                new SpringManagedTransactionFactory(), dataSource));
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        try (var input = new ClassPathResource("contract-baseline/reads.xml").getInputStream()) {
            new XMLMapperBuilder(input, configuration, "contract-baseline/reads.xml",
                    configuration.getSqlFragments()).parse();
        }
        return new SqlSessionTemplate(new SqlSessionFactoryBuilder().build(configuration));
    }

    private void compare(String name, int expectedSqlCount, Supplier<?> oldRead, Supplier<?> newRead) {
        // 응답 행·순서·총건수·타입·null을 비교한다. JSON 변환과 검증 시간은 측정에서 제외한다.
        assertThat(objectMapper.<JsonNode>valueToTree(newRead.get()))
                .isEqualTo(objectMapper.<JsonNode>valueToTree(oldRead.get()));
        for (int i = 0; i < WARMUPS; i++) {
            if (i % 2 == 0) { oldRead.get(); newRead.get(); }
            else { newRead.get(); oldRead.get(); }
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        long loadedBefore = statistics.getEntityLoadCount();
        List<Sample> oldSamples = new ArrayList<>();
        List<Sample> newSamples = new ArrayList<>();
        for (int i = 0; i < SAMPLES; i++) {
            if (i % 2 == 0) {
                oldSamples.add(measure(oldRead)); newSamples.add(measure(newRead));
            } else {
                newSamples.add(measure(newRead)); oldSamples.add(measure(oldRead));
            }
        }
        assertThat(oldSamples).allSatisfy(s -> assertThat(s.sql().size()).isEqualTo(expectedSqlCount));
        assertThat(newSamples).allSatisfy(s -> assertThat(s.sql().size()).isEqualTo(expectedSqlCount));
        assertThat(statistics.getEntityLoadCount() - loadedBefore).isZero();
        System.out.printf(Locale.ROOT,
                "CONTRACT_PERF %s sql=%d/%d old_p50_ms=%.3f new_p50_ms=%.3f old_p95_ms=%.3f new_p95_ms=%.3f entity_loads=0 samples=%d%n",
                name, expectedSqlCount, expectedSqlCount, percentile(oldSamples, .50), percentile(newSamples, .50),
                percentile(oldSamples, .95), percentile(newSamples, .95), SAMPLES);
        System.out.println("CONTRACT_SQL_OLD " + name + " " + oldSamples.getFirst().sql());
        System.out.println("CONTRACT_SQL_NEW " + name + " " + newSamples.getFirst().sql());
    }

    private Sample measure(Supplier<?> read) {
        List<String> statements = new ArrayList<>();
        EXECUTED_SQL.set(statements);
        try {
            long start = System.nanoTime();
            Object result = read.get();
            long elapsed = System.nanoTime() - start;
            assertThat(result).isNotNull();
            return new Sample(elapsed, statements);
        } finally {
            EXECUTED_SQL.remove();
        }
    }

    private double percentile(List<Sample> samples, double percentile) {
        long[] values = samples.stream().mapToLong(Sample::nanos).toArray();
        Arrays.sort(values);
        return values[(int) Math.ceil(values.length * percentile) - 1] / 1_000_000.0;
    }

    private void addRepresentativeRows(Long contractId) {
        String marker = "PERF-" + UUID.randomUUID().toString().substring(0, 8);
        // 상품·설계사별 제약을 만족하는 기존 계약을 복사하되 식별값은 측정 전용으로 만든다.
        jdbc.update("""
                INSERT INTO fgc.insurance_contract
                    (insurer_id, product_offering_id, contract_no, contract_date, agent_id, organization_id,
                     premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                     premium_conversion_rule_code, payment_cycle_code, payment_term_months,
                     standard_surrender_deduction_amount, current_status, data_origin)
                SELECT insurer_id, product_offering_id, ? || '-' || n, contract_date, agent_id, organization_id,
                       premium_per_cycle_amount, first_premium_amount, monthly_equivalent_first_premium,
                       premium_conversion_rule_code, payment_cycle_code, payment_term_months,
                       standard_surrender_deduction_amount, current_status, data_origin
                  FROM fgc.insurance_contract CROSS JOIN generate_series(1, 5000) n WHERE contract_id = ?
                """, marker, contractId);
        jdbc.update("""
                INSERT INTO fgc.journal_header
                    (journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                     revision_no, contract_id, description)
                SELECT ? || '-' || n, DATE '2026-07-31', 'EXPECTED_INSURER_INCOME', 'SCHEDULE_LINE',
                       ? || '-' || n, 1, ?, ? FROM generate_series(1, 100) n
                """, marker, marker, contractId, marker);
        jdbc.update("""
                INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                SELECT h.journal_header_id, n,
                       (SELECT journal_account_id FROM fgc.journal_account WHERE account_code =
                           CASE WHEN n = 1 THEN 'EXPECTED_RECEIVABLE' ELSE 'EXPECTED_INCOME' END),
                       CASE WHEN n = 1 THEN 50000 ELSE 0 END, CASE WHEN n = 2 THEN 50000 ELSE 0 END
                  FROM fgc.journal_header h CROSS JOIN generate_series(1, 2) n WHERE h.description = ?
                """, marker);
        // 양쪽 조회가 같은 통계를 사용하게 한다. 픽스처 롤백 후 통계도 다시 수집한다.
        jdbc.execute("ANALYZE fgc.insurance_contract");
        jdbc.execute("ANALYZE fgc.journal_header");
        jdbc.execute("ANALYZE fgc.journal_line");
    }

    @AfterTransaction
    void refreshStatisticsAfterFixtureRollback() {
        jdbc.execute("ANALYZE fgc.insurance_contract");
        jdbc.execute("ANALYZE fgc.journal_header");
        jdbc.execute("ANALYZE fgc.journal_line");
    }

    private record Sample(long nanos, List<String> sql) { }

    @TestConfiguration(proxyBeanMethods = false)
    static class SqlObservationConfig {
        @Bean
        static BeanPostProcessor observedDataSource() {
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
                        if (result instanceof Statement statement
                                && (method.getName().equals("prepareStatement") || method.getName().equals("createStatement"))) {
                            String sql = args != null && args.length > 0 && args[0] instanceof String s ? s : null;
                            Class<?> type = statement instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
                            return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (p, m, a) -> {
                                List<String> collected = EXECUTED_SQL.get();
                                if (collected != null && m.getName().startsWith("execute")) {
                                    String executed = sql != null ? sql : String.valueOf(a[0]);
                                    collected.add(executed.replaceAll("\\s+", " ").trim());
                                }
                                try { return m.invoke(statement, a); }
                                catch (InvocationTargetException exception) { throw exception.getCause(); }
                            });
                        }
                        return result;
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
    }
}
