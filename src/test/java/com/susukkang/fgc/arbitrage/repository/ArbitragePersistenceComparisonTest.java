package com.susukkang.fgc.arbitrage.repository;

import com.susukkang.fgc.arbitrage.baseline.ArbitrageBaselineMapper;
import com.susukkang.fgc.arbitrage.dto.*;
import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.transaction.performance.PaymentJdbcObservation;
import jakarta.persistence.EntityManager;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/** 같은 PostgreSQL 데이터에서 기존 MyBatis와 JPA 결과·SQL 실행 수를 비교한다. */
@SpringBootTest
@Transactional
@Import(PaymentJdbcObservation.Config.class)
class ArbitragePersistenceComparisonTest {
    private static final Logger log = LoggerFactory.getLogger(ArbitragePersistenceComparisonTest.class);
    @Autowired ArbitrageCheckRepository repository;
    @Autowired SqlSessionFactory sqlSessionFactory;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager entityManager;
    @Autowired ExceptionCaseRepository exceptionRepository;
    @Autowired com.susukkang.fgc.validation.mapper.ExceptionCaseMapper oldExceptionMapper;
    @Autowired com.susukkang.fgc.arbitrage.service.ArbitrageService service;
    @Autowired com.susukkang.fgc.validation.service.ValidationRunCreateService createService;
    @Autowired com.susukkang.fgc.validation.mapper.ValidationRunMapper oldRunMapper;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @Autowired com.susukkang.fgc.audit.service.AuditLogService auditLogService;
    private ArbitrageBaselineMapper baseline;

    @BeforeEach
    void loadTestOnlyBaseline() throws Exception {
        var configuration = sqlSessionFactory.getConfiguration();
        synchronized (configuration) {
            if (!configuration.hasMapper(ArbitrageBaselineMapper.class)) {
                var resource = new ClassPathResource("arbitrage-baseline/ArbitrageBaselineMapper.xml");
                try (var stream = resource.getInputStream()) {
                    new XMLMapperBuilder(stream, configuration, resource.toString(), configuration.getSqlFragments()).parse();
                }
            }
        }
        baseline = new SqlSessionTemplate(sqlSessionFactory).getMapper(ArbitrageBaselineMapper.class);
    }

    @Test
    void preservesInputAmountsSnapshotSelectionAndRefundCandidatesForBothStages() {
        List<Reference> references = jdbc.query("""
                SELECT c.contract_id, c.contract_date, MAX(fs.as_of_date) AS as_of_date
                  FROM fgc.insurance_contract c
                  JOIN fgc.contract_financial_snapshot fs ON fs.contract_id = c.contract_id
                 GROUP BY c.contract_id, c.contract_date ORDER BY c.contract_id
                """, (rs, i) -> new Reference(rs.getLong(1), rs.getObject(2, LocalDate.class), rs.getObject(3, LocalDate.class)));
        assertThat(references).isNotEmpty();
        for (Reference reference : references) {
            for (LocalDate date : List.of(reference.contractDate().minusDays(1), reference.asOfDate(), reference.asOfDate().plusDays(1))) {
                var oldSource = baseline.selectCalculationSource(reference.contractId(), date);
                var newSource = repository.selectCalculationSource(reference.contractId(), date);
                same(newSource, oldSource);
                same(repository.selectRefundRateCandidates(newSource, date), baseline.selectRefundRateCandidates(oldSource, date));
                for (PaymentStage stage : PaymentStage.values()) {
                    same(repository.sumConfirmedCommissionAmount(reference.contractId(), stage, date),
                            baseline.sumConfirmedCommissionAmount(reference.contractId(), stage, date));
                    same(repository.sumPlannedCommissionAmount(reference.contractId(), stage),
                            baseline.sumPlannedCommissionAmount(reference.contractId(), stage));
                }
            }
        }
        assertThat(repository.selectCalculationSource(Long.MAX_VALUE, LocalDate.now())).isNull();
    }

    @Test
    void preservesLatestResultsAllFiltersPaginationSummaryAndTimeline() {
        LocalDate date = LocalDate.of(2095, 7, 31);
        Long contractId = firstContract();
        String contractNo = jdbc.queryForObject("SELECT contract_no FROM fgc.insurance_contract WHERE contract_id = ?", String.class, contractId);
        Long insurerId = jdbc.queryForObject("SELECT insurer_id FROM fgc.insurance_contract WHERE contract_id = ?", Long.class, contractId);
        var row = row(insertRun(), contractId, date);
        repository.insertArbitrageCheck(row);
        row.setValidationRunId(insertRun());
        row.setResultStatus(ArbitrageCheckStatus.CANDIDATE);
        row.setCalculationSnapshot("{\"decisionReason\":\"최신 판정\"}");
        repository.insertArbitrageCheck(row);
        row.setAsOfDate(date.plusDays(1));
        row.setResultStatus(ArbitrageCheckStatus.REVIEW_REQUIRED);
        repository.insertArbitrageCheck(row);
        for (PaymentStage stage : PaymentStage.values()) {
            for (ArbitrageCheckStatus status : ArbitrageCheckStatus.values()) {
                for (YearMonth month : new YearMonth[]{null, YearMonth.from(date), YearMonth.from(date.plusDays(1))}) {
                    var condition = new ArbitrageCheckSearchCondition(month, status, stage, insurerId, contractNo);
                    for (int offset : new int[]{0, 1, 100}) {
                        same(repository.selectByCondition(condition, offset, 1), baseline.selectByCondition(condition, offset, 1));
                    }
                    same(repository.arbitrageCheckSummary(condition), baseline.arbitrageCheckSummary(condition));
                    assertThat(repository.countByCondition(condition)).isEqualTo(baseline.countByCondition(condition));
                }
            }
            same(repository.selectByContractId(contractId, stage), baseline.selectByContractId(contractId, stage));
        }
        var missing = new ArbitrageCheckSearchCondition(null, null, null, null, "NO-SUCH-CONTRACT");
        assertThat(repository.selectByCondition(missing, 0, 20)).isEmpty();
        assertThat(repository.countByCondition(missing)).isZero();
        assertThat(repository.arbitrageCheckSummary(missing).getTotalArbitrageChecks()).isZero();
    }

    @Test
    void upsertReturnsExistingIdAndRefreshesPreviouslyLoadedJpaResult() {
        var row = row(insertRun(), firstContract(), LocalDate.of(2095, 7, 31));
        repository.insertArbitrageCheck(row);
        Long id = row.getArbitrageCheckId();
        var loaded = repository.findById(id).orElseThrow();
        assertThat(loaded.getResultStatus()).isEqualTo(ArbitrageCheckStatus.CLEAR);
        row.setResultStatus(ArbitrageCheckStatus.REVIEW_REQUIRED);
        row.setCalculationSnapshot("{\"decisionReason\":null,\"amount\":123.45}");
        repository.insertArbitrageCheck(row);
        assertThat(row.getArbitrageCheckId()).isEqualTo(id);
        assertThat(entityManager.contains(loaded)).isFalse();
        var updated = repository.findById(id).orElseThrow();
        assertThat(updated.getResultStatus()).isEqualTo(ArbitrageCheckStatus.REVIEW_REQUIRED);
        assertThat(updated.getCreatedAt()).isEqualTo(loaded.getCreatedAt());
        assertThat(updated.getRefundRateTableId()).isNull();
        assertThat(updated.getCalculationSnapshot()).contains("123.45");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.arbitrage_check WHERE validation_run_id = ? AND contract_id = ?", Long.class,
                row.getValidationRunId(), row.getContractId())).isEqualTo(1L);
        // 기존 UPSERT도 같은 id를 돌려주는지 동일 업무키로 확인한다.
        baseline.insertArbitrageCheck(row);
        assertThat(row.getArbitrageCheckId()).isEqualTo(id);
    }

    @Test
    void preservesExceptionBusinessKeyRedetectionReopeningAndEvidence() {
        var row = row(insertRun(), firstContract(), LocalDate.of(2095, 7, 31));
        repository.insertArbitrageCheck(row);
        Long id = exceptionRepository.insertArbitrageCandidate(row.getValidationRunId(), row.getContractId(), row.getArbitrageCheckId(), "GA_TO_FC", "검토 근거");
        Long oldId = oldExceptionMapper.insertArbitrageCandidate(row.getValidationRunId(), row.getContractId(), row.getArbitrageCheckId(), "GA_TO_FC", "검토 근거");
        assertThat(id).isEqualTo(oldId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.exception_occurrence WHERE exception_case_id = ?", Long.class, id)).isEqualTo(1);
        String key = jdbc.queryForObject("SELECT exception_key FROM fgc.exception_case WHERE exception_case_id = ?", String.class, id);
        assertThat(key).isEqualTo("ARBITRAGE_CANDIDATE:2095-07:CONTRACT:" + row.getContractId() + ":GA_TO_FC");
        jdbc.update("UPDATE fgc.exception_case SET status = 'RESOLVED', resolved_at = clock_timestamp() WHERE exception_case_id = ?", id);
        row.setValidationRunId(insertRun());
        repository.insertArbitrageCheck(row);
        Long redetectedId = exceptionRepository.insertArbitrageCandidate(row.getValidationRunId(), row.getContractId(), row.getArbitrageCheckId(), "GA_TO_FC", "재검출 근거");
        assertThat(redetectedId).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT status FROM fgc.exception_case WHERE exception_case_id = ?", String.class, id)).isEqualTo("NEW");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.exception_occurrence WHERE exception_case_id = ?", Long.class, id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT was_reopened FROM fgc.exception_occurrence WHERE exception_case_id = ? AND validation_run_id = ?", Boolean.class, id, row.getValidationRunId())).isTrue();
        Long reviewId = exceptionRepository.insertArbitrageReviewCase("DATA_QUALITY", row.getValidationRunId(), row.getContractId(), row.getArbitrageCheckId(), "GA_TO_FC", "확인 필요", "기준일 이하 계약 금융 스냅샷이 없습니다.");
        assertThat(reviewId).isEqualTo(oldExceptionMapper.insertArbitrageReviewCase("DATA_QUALITY", row.getValidationRunId(), row.getContractId(), row.getArbitrageCheckId(), "GA_TO_FC", "확인 필요", "기준일 이하 계약 금융 스냅샷이 없습니다."));
        assertThat(jdbc.queryForObject("SELECT reason_code FROM fgc.exception_case WHERE exception_case_id = ?", String.class, reviewId)).isEqualTo("FINANCIAL_SNAPSHOT_MISSING");
    }

    @Test
    void comparesInputListAndUpsertJdbcExecutionsAndElapsedTime() {
        Long contractId = firstContract();
        LocalDate date = jdbc.queryForObject("SELECT MAX(as_of_date) FROM fgc.contract_financial_snapshot WHERE contract_id = ?", LocalDate.class, contractId);
        var condition = new ArbitrageCheckSearchCondition(null, null, PaymentStage.GA_TO_FC, null, null);
        // 동일 SELECT를 워밍업한 뒤 번갈아 측정한다. DB 시간과 JVM/매핑 시간을 구분한다.
        for (int i = 0; i < 3; i++) { input(false, contractId, date); input(true, contractId, date); list(false, condition); list(true, condition); }
        compare("input", () -> input(false, contractId, date), () -> input(true, contractId, date), 4);
        compare("list", () -> list(false, condition), () -> list(true, condition), 3);
        var row = row(insertRun(), contractId, date);
        compare("upsert", () -> baseline.insertArbitrageCheck(row), () -> repository.insertArbitrageCheck(row), 1);
        var oldService = new com.susukkang.fgc.arbitrage.baseline.ArbitrageBaselineService(
                baseline, createService, oldRunMapper, oldExceptionMapper, objectMapper, auditLogService);
        Long batchRunId = insertRun();
        Supplier<?> oldBatch = () -> oldService.checkInExistingRun(batchRunId, contractId, date);
        Supplier<?> newBatch = () -> service.checkInExistingRun(batchRunId, contractId, date);
        int batchSql = measure(oldBatch).sqlCount();
        compare("contract-processing", oldBatch, newBatch, batchSql);
    }

    private Object input(boolean jpa, Long contractId, LocalDate date) {
        var source = jpa ? repository.selectCalculationSource(contractId, date) : baseline.selectCalculationSource(contractId, date);
        var confirmed = jpa ? repository.sumConfirmedCommissionAmount(contractId, PaymentStage.GA_TO_FC, date) : baseline.sumConfirmedCommissionAmount(contractId, PaymentStage.GA_TO_FC, date);
        var planned = jpa ? repository.sumPlannedCommissionAmount(contractId, PaymentStage.GA_TO_FC) : baseline.sumPlannedCommissionAmount(contractId, PaymentStage.GA_TO_FC);
        var refund = jpa ? repository.selectRefundRateCandidates(source, date) : baseline.selectRefundRateCandidates(source, date);
        return List.of(source, confirmed, planned, refund);
    }

    private Object list(boolean jpa, ArbitrageCheckSearchCondition condition) {
        return jpa ? List.of(repository.selectByCondition(condition, 0, 20), repository.arbitrageCheckSummary(condition), repository.countByCondition(condition))
                : List.of(baseline.selectByCondition(condition, 0, 20), baseline.arbitrageCheckSummary(condition), baseline.countByCondition(condition));
    }

    private void compare(String name, Supplier<?> oldOperation, Supplier<?> newOperation, int expectedSql) {
        long oldNanos = 0, newNanos = 0;
        for (int i = 0; i < 10; i++) {
            Measurement oldMeasurement, newMeasurement;
            if (i % 2 == 0) { oldMeasurement = measure(oldOperation); newMeasurement = measure(newOperation); }
            else { newMeasurement = measure(newOperation); oldMeasurement = measure(oldOperation); }
            same(newMeasurement.result(), oldMeasurement.result());
            assertThat(oldMeasurement.sqlCount()).isEqualTo(expectedSql);
            assertThat(newMeasurement.sqlCount()).isEqualTo(expectedSql);
            oldNanos += oldMeasurement.nanos(); newNanos += newMeasurement.nanos();
        }
        log.info("ARBITRAGE_COMPARISON {} samples=10 sql={}/{} avgMs(MyBatis/JPA)={}/{}", name, expectedSql, expectedSql,
                oldNanos / 10.0 / 1_000_000, newNanos / 10.0 / 1_000_000);
    }

    private Measurement measure(Supplier<?> operation) {
        // MyBatis 세션 캐시를 비우고 양쪽 모두 실제 DB 호출을 측정한다.
        new SqlSessionTemplate(sqlSessionFactory).clearCache();
        PaymentJdbcObservation.start();
        long started = System.nanoTime();
        Object result;
        PaymentJdbcObservation.Snapshot snapshot;
        try { result = operation.get(); }
        finally { snapshot = PaymentJdbcObservation.stop(); }
        return new Measurement(result, System.nanoTime() - started, snapshot.executions().size());
    }

    private void same(Object actual, Object expected) {
        assertThat(actual).usingRecursiveComparison().withComparatorForType(BigDecimal::compareTo, BigDecimal.class).isEqualTo(expected);
    }

    private Long firstContract() {
        return jdbc.queryForObject("SELECT contract_id FROM fgc.contract_financial_snapshot ORDER BY contract_id LIMIT 1", Long.class);
    }

    private Long insertRun() {
        return jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT DATE '2095-07-01', COALESCE(MAX(run_no), 0) + 1, 'PRE_CONFIRM', 'CREATED'
                  FROM fgc.validation_run WHERE validation_month = DATE '2095-07-01'
                RETURNING validation_run_id
                """, Long.class);
    }

    private ArbitrageCheckInsertDTO row(Long runId, Long contractId, LocalDate date) {
        return ArbitrageCheckInsertDTO.builder().validationRunId(runId).contractId(contractId).paymentStage(PaymentStage.GA_TO_FC)
                .asOfDate(date).contractMonthNo(12).cumulativePaidPremium(new BigDecimal("1200000"))
                .paidCommissionAmount(new BigDecimal("900000")).plannedCommissionAmount(new BigDecimal("400000"))
                .includedSurrenderValueAmount(BigDecimal.ZERO).refundAdditionAppliedYn(false)
                .surrenderValueSourceType(SurrenderValueSourceType.NOT_APPLICABLE).netDifferenceAmount(new BigDecimal("100000"))
                .standardDeduction80Yn(false).resultStatus(ArbitrageCheckStatus.CLEAR).calculationSnapshot("{\"decisionReason\":\"기존 판정\"}").build();
    }

    private record Reference(Long contractId, LocalDate contractDate, LocalDate asOfDate) { }
    private record Measurement(Object result, long nanos, int sqlCount) { }
}
