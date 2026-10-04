package com.susukkang.fgc.reconciliation.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.dto.*;
import com.susukkang.fgc.transaction.performance.PaymentJdbcObservation;
import jakarta.persistence.EntityManager;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.mybatis.spring.transaction.SpringManagedTransactionFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : b5b4692의 격리된 MyBatis SQL과 같은 DB·트랜잭션에서 원천·결과 projection을 비교한다.
 * JDBC 계측은 기존 테스트 도구를 재사용하며 호출 시간은 DB 서버 실행시간과 다르다.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
@SpringBootTest
@Transactional
@Import(PaymentJdbcObservation.Config.class)
class ReconciliationRepositoryCompatibilityIntegrationTest {
    private static final String NS = "com.susukkang.fgc.reconciliation.mapper.";
    @Autowired private DataSource dataSource;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private EntityManager entityManager;
    @Autowired private InsurerGaReconciliationRepository insurerSources;
    @Autowired private GaFcReconciliationRepository fcSources;
    @Autowired private ReconciliationRunRepository runs;
    @Autowired private ReconciliationRunJpaRepository runEntities;
    @Autowired private ReconciliationResultRepository results;
    @Autowired private ReconciliationResultJpaRepository resultEntities;
    @Autowired private ReconciliationMatchJpaRepository matchEntities;
    @Autowired private ReconciliationRunHistoryRepository history;
    private SqlSessionTemplate baseline;

    @BeforeEach
    void isolatedBaseline() throws Exception {
        Configuration configuration = new Configuration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setCacheEnabled(false);
        SqlSessionFactoryBean factory = new SqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        factory.setTransactionFactory(new SpringManagedTransactionFactory());
        factory.setMapperLocations(new PathMatchingResourcePatternResolver()
                .getResources("classpath:reconciliation-baseline/*.xml"));
        baseline = new SqlSessionTemplate(factory.getObject());
    }

    @Test
    void comparesBothDirectionsAgainstOriginalSqlAndKeepsOneSelectPerProjection() {
        Long insurerId = jdbc.queryForObject("SELECT MIN(insurer_id) FROM fgc.insurance_contract", Long.class);
        LocalDate month = LocalDate.of(2026, 7, 1);
        Map<String, Object> parameters = params("settlementMonth", month, "insurerId", insurerId);
        compare("insurer_expected", () -> baseline.selectList(NS + "InsurerGaReconciliationMapper.findExpectedSources", parameters),
                () -> insurerSources.findExpectedSources(month, insurerId));
        compare("insurer_actual", () -> baseline.selectList(NS + "InsurerGaReconciliationMapper.findActualSources", parameters),
                () -> insurerSources.findActualSources(month, insurerId));
        compare("fc_expected", () -> baseline.selectList(NS + "GaFcReconciliationMapper.findExpectedSources", parameters),
                () -> fcSources.findExpectedSources(month, insurerId));
        compare("fc_actual", () -> baseline.selectList(NS + "GaFcReconciliationMapper.findActualSources", parameters),
                () -> fcSources.findActualSources(month, insurerId));
        assertThat(fcSources.findExpectedSources(month, insurerId)).isNotEmpty();
        compare("empty_source", () -> baseline.selectList(NS + "GaFcReconciliationMapper.findActualSources",
                        params("settlementMonth", month, "insurerId", Long.MAX_VALUE)),
                () -> fcSources.findActualSources(month, Long.MAX_VALUE));
    }

    @Test
    void mapsEntitiesAndPreservesNullsArraysJsonDuplicateSnapshotsAndRunTransitions() {
        Long id = createRun();
        var managedRun = runEntities.findById(id).orElseThrow();
        assertThat(managedRun.getPaymentStage()).isEqualTo(PaymentStage.GA_TO_FC);
        assertThat(managedRun.getCreatedAt()).isNotNull();
        assertThat(runs.transitionToRunning(id)).isOne();
        assertThat(runs.transitionToRunning(id)).isZero();
        assertThat(runs.findById(id).getStatus()).isEqualTo("RUNNING");
        compare("run_projection", () -> baseline.selectOne(NS + "ReconciliationRunMapper.findById",
                params("reconciliationRunId", id)), () -> runs.findById(id));

        ReconciliationResultInsertRow row = result(id);
        row.setSecondaryReasonCodes(List.of("quoted\"reason", "comma,reason", "한글"));
        row.setDetailSnapshotJson("{\"sources\":[],\"nested\":{\"value\":null},\"text\":\"한글\"}");
        assertThat(results.insertResult(row)).isOne();
        Long resultId = row.getReconciliationResultId();
        var stored = resultEntities.findById(resultId).orElseThrow();
        assertThat(stored.getSecondaryReasonCodes()).containsExactly("quoted\"reason", "comma,reason", "한글");
        assertThat(stored.getContractId()).isNull();
        assertThat(stored.getExpectedTotalAmount()).isEqualByComparingTo("101.50");
        assertThat(stored.getCreatedAt()).isNotNull();
        String originalSnapshot = results.findDetail(resultId).getDetailSnapshotJson();
        row.setDetailSnapshotJson("{\"changed\":true}");
        row.setExpectedTotalAmount(new BigDecimal("999"));
        assertThat(results.insertResult(row)).isZero();
        assertThat(row.getReconciliationResultId()).isNull();
        assertThat(results.findResultId(id, row.getMatchGroupKey())).isEqualTo(resultId);
        assertThat(results.findDetail(resultId).getDetailSnapshotJson()).isEqualTo(originalSnapshot);
        assertThat(results.findDetail(resultId).getExpectedTotalAmount()).isEqualByComparingTo("101.50");

        Long lineId = jdbc.queryForObject("SELECT MIN(schedule_line_id) FROM fgc.schedule_line", Long.class);
        ReconciliationMatchInsertRow match = new ReconciliationMatchInsertRow(resultId, 1, lineId, null,
                new BigDecimal("101.50"), "EXPECTED");
        assertThat(results.insertMatch(match)).isOne();
        assertThat(results.insertMatch(match)).isZero();
        Long matchId = jdbc.queryForObject("SELECT reconciliation_match_id FROM fgc.reconciliation_match WHERE reconciliation_result_id = ?",
                Long.class, resultId);
        assertThat(matchEntities.findById(matchId).orElseThrow().getMatchedAmount()).isEqualByComparingTo("101.50");
        compare("result_detail", () -> baseline.selectOne(NS + "ReconciliationResultMapper.findDetail",
                params("reconciliationResultId", resultId)), () -> results.findDetail(resultId));
        compare("match_detail", () -> baseline.selectList(NS + "ReconciliationResultMapper.findMatches",
                params("reconciliationResultId", resultId)), () -> results.findMatches(resultId));
        compare("summary", () -> baseline.selectOne(NS + "ReconciliationResultMapper.findSummary",
                params("reconciliationRunId", id)), () -> results.findSummary(id));
        assertThat(runs.transitionToFailed(id)).isOne();
        assertThat(runs.transitionToRunning(id)).isOne();
        assertThat(runs.findById(id).getCompletedAt()).isNull();
        assertThat(runs.transitionToCompleted(id)).isOne();
        assertThat(runs.transitionToFailed(id)).isZero();
        assertThat(runs.findById(id).getStatus()).isEqualTo("COMPLETED");
        assertThat(entityManager.contains(managedRun)).isFalse();
        assertThat(runEntities.findById(id).orElseThrow().getStatus().name()).isEqualTo("COMPLETED");
    }

    @Test
    void comparesNullableAndSqlLikeFiltersEmptyResultsAndClassificationContext() {
        Long id = createRun();
        assertThat(results.insertResult(result(id))).isOne();
        for (String filter : new String[]{null, "MATCHED", "", "' OR 1=1 --"}) {
            for (String direction : List.of("asc", "desc")) {
                Map<String, Object> parameters = params("reconciliationRunId", id, "resultType", filter,
                        "sortDirection", direction, "offset", 0, "limit", 20);
                compare("result_filter", () -> baseline.selectList(NS + "ReconciliationResultMapper.findResults", parameters),
                        () -> results.findResults(id, filter, direction, 0, 20));
                assertThat(results.countResults(id, filter)).isEqualTo(
                        ((Number) baseline.selectOne(NS + "ReconciliationResultMapper.countResults", parameters)).longValue());
            }
        }
        for (String stage : new String[]{null, "GA_TO_FC", "", "' OR 1=1 --"}) {
            Map<String, Object> p = params("settlementMonth", null, "paymentStage", stage, "insurerId", null,
                    "sortDirection", "asc", "offset", 0, "limit", 20);
            compare("history_filter", () -> baseline.selectList(NS + "ReconciliationRunHistoryMapper.search", p),
                    () -> history.search(null, stage, null, "asc", 0, 20));
            assertThat(history.count(null, stage, null)).isEqualTo(
                    ((Number) baseline.selectOne(NS + "ReconciliationRunHistoryMapper.count", p)).longValue());
        }
        for (List<Long> journalIds : List.of(List.<Long>of(), List.of(Long.MAX_VALUE))) {
            Map<String, Object> p = params("contractId", null, "expectedAgentId", null,
                    "actualAgentId", null, "journalHeaderIds", journalIds);
            compare("classification_context", () -> baseline.selectOne(NS + "ReconciliationResultMapper.findClassificationContext", p),
                    () -> results.findClassificationContext(null, null, null, journalIds));
        }
        assertThat(results.findDetail(Long.MAX_VALUE)).isNull();
        assertThat(runs.findById(Long.MAX_VALUE)).isNull();
        assertThat(results.findResults(id, null, "asc", 100, 20)).isEmpty();
    }

    private Long createRun() {
        ReconciliationRunInsertRow row = new ReconciliationRunInsertRow();
        row.setSettlementMonth(LocalDate.of(2093, 5, 1));
        row.setPaymentStage(PaymentStage.GA_TO_FC);
        runs.insert(row);
        return row.getReconciliationRunId();
    }

    @Test
    void readsMyBatisWritesAndJpaWritesInTheSameTransactionWithoutEntityStateOverwrites() {
        Long id = createRun();
        ReconciliationResultInsertRow original = result(id);
        original.setSecondaryReasonCodes(List.of("AMOUNT_DIFFERENCE"));
        baseline.insert(NS + "ReconciliationResultMapper.insertResult", original);
        assertThat(original.getReconciliationResultId()).isNotNull();
        assertThat(resultEntities.findById(original.getReconciliationResultId()).orElseThrow()
                .getSecondaryReasonCodes()).containsExactly("AMOUNT_DIFFERENCE");
        assertThat(results.countByRunId(id)).isOne();
        assertThat(results.insertResult(original)).isZero();
        assertThat(results.countByRunId(id)).isOne();
        baseline.update(NS + "ReconciliationRunMapper.transitionToRunning", params("reconciliationRunId", id));
        assertThat(runs.findById(id).getStatus()).isEqualTo("RUNNING");
        assertThat(runs.transitionToCompleted(id)).isOne();
        ReconciliationRunRow after = baseline.selectOne(NS + "ReconciliationRunMapper.findById",
                params("reconciliationRunId", id));
        assertThat(after.getStatus()).isEqualTo("COMPLETED");
        assertThat(after.getCompletedAt()).isNotNull();
    }

    private ReconciliationResultInsertRow result(Long runId) {
        ReconciliationResultInsertRow row = new ReconciliationResultInsertRow();
        row.setReconciliationRunId(runId);
        row.setMatchGroupKey("JPA-377-" + UUID.randomUUID());
        row.setResultType("MATCHED");
        row.setExpectedTotalAmount(new BigDecimal("101.50"));
        row.setActualTotalAmount(new BigDecimal("101.50"));
        row.setDifferenceAmount(BigDecimal.ZERO);
        row.setDetailSnapshotJson("{}");
        return row;
    }

    private <T> void compare(String operation, Supplier<T> oldQuery, Supplier<? extends T> newQuery) {
        // 한 번 예열한 뒤 동일 DB 결과·실제 JDBC 실행 횟수를 비교한다. 시간 임계값은 CI에 강제하지 않는다.
        oldQuery.get();
        newQuery.get();
        PaymentJdbcObservation.start();
        T previous;
        PaymentJdbcObservation.Snapshot oldStats;
        try { previous = oldQuery.get(); } finally { oldStats = PaymentJdbcObservation.stop(); }
        PaymentJdbcObservation.start();
        T current;
        PaymentJdbcObservation.Snapshot newStats;
        try { current = newQuery.get(); } finally { newStats = PaymentJdbcObservation.stop(); }
        assertThat(current).as(operation).usingRecursiveComparison().isEqualTo(previous);
        assertThat(oldStats.executions()).as(operation + " baseline SQL count").hasSize(1);
        assertThat(newStats.executions()).as(operation + " JPA SQL count").hasSize(1);
        org.slf4j.LoggerFactory.getLogger(getClass()).info(
                "RECONCILIATION_AB operation={} mybatis_jdbc_ns={} jpa_jdbc_ns={} mybatis_sql={} jpa_sql={}",
                operation, oldStats.jdbcNanos(), newStats.jdbcNanos(),
                oldStats.executions().size(), newStats.executions().size());
    }

    private static Map<String, Object> params(Object... values) {
        Map<String, Object> parameters = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) parameters.put((String) values[i], values[i + 1]);
        return parameters;
    }
}
