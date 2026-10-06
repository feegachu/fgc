package com.susukkang.fgc.arbitrage.repository;

import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckInsertDTO;
import com.susukkang.fgc.arbitrage.service.ArbitrageCheckBatchAdapter;
import com.susukkang.fgc.arbitrage.service.ArbitrageCheckBatchItemService;
import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.SurrenderValueSourceType;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 커밋·잠금·확정 실행 거부를 개발 DB와 분리된 PostgreSQL에서 검증한다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_arbitrage_write_guards?currentSchema=fgc")
class ArbitrageWriteGuardsIntegrationTest {
    @Autowired ArbitrageCheckRepository repository;
    @Autowired ArbitrageCheckBatchItemService itemService;
    @Autowired ArbitrageCheckBatchAdapter adapter;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactionManager;

    @Test
    void concurrentUpsertsWaitForTheSameKeyAndReturnOneResultId() throws Exception {
        LocalDate month = LocalDate.of(2094, 3, 1);
        Long runId = insertRun(month);
        Long contractId = jdbc.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
        LocalDate asOfDate = month.plusMonths(1).minusDays(1);
        var firstRow = row(runId, contractId, asOfDate, "first");
        var secondRow = row(runId, contractId, asOfDate, "second");
        CountDownLatch firstInserted = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);
        AtomicInteger firstPid = new AtomicInteger();
        AtomicInteger secondPid = new AtomicInteger();

        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.execute("SET LOCAL statement_timeout = '15s'");
                firstPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                repository.insertArbitrageCheck(firstRow);
                firstInserted.countDown();
                awaitLatch(commitFirst);
                return firstRow.getArbitrageCheckId();
            }));
            try {
                awaitLatch(firstInserted);
                var second = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                    jdbc.execute("SET LOCAL statement_timeout = '15s'");
                    secondPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                    secondStarted.countDown();
                    repository.insertArbitrageCheck(secondRow);
                    return secondRow.getArbitrageCheckId();
                }));
                awaitLatch(secondStarted);
                // 실제 DB 잠금 대기를 확인하므로 우연히 순차 실행된 두 호출로 통과하지 않는다.
                assertThat(waitUntilBlocked(firstPid.get(), secondPid.get())).isTrue();
                commitFirst.countDown();
                Long firstId = first.get(10, TimeUnit.SECONDS);
                Long secondId = second.get(10, TimeUnit.SECONDS);
                assertThat(firstId).isNotNull().isEqualTo(secondId);
                assertThat(jdbc.queryForObject("""
                        SELECT COUNT(*) FROM fgc.arbitrage_check
                         WHERE validation_run_id = ? AND contract_id = ?
                           AND payment_stage = 'GA_TO_FC' AND as_of_date = ?
                        """, Long.class, runId, contractId, asOfDate)).isEqualTo(1L);
                assertThat(jdbc.queryForObject("""
                        SELECT calculation_snapshot ->> 'writer' FROM fgc.arbitrage_check
                         WHERE arbitrage_check_id = ?
                        """, String.class, firstId)).isEqualTo("second");
            } finally {
                commitFirst.countDown();
            }
        }
    }

    @Test
    void finalizedRunRejectsExistingAndNewContractWritesWithoutChangingResultsOrEvidence() {
        LocalDate month = LocalDate.of(2094, 4, 1);
        LocalDate asOfDate = month.plusMonths(1).minusDays(1);
        Long frozenRunId = insertRun(month);
        var contractIds = jdbc.queryForList(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 2", Long.class);
        assertThat(contractIds).hasSize(2);
        Long firstContractId = contractIds.getFirst();
        Long secondContractId = contractIds.get(1);
        var original = itemService.process(frozenRunId, firstContractId, asOfDate);
        jdbc.update("""
                INSERT INTO fgc.validation_target
                    (validation_run_id, contract_id, product_offering_id, selection_status)
                SELECT ?, contract_id, product_offering_id, 'SELECTED'
                  FROM fgc.insurance_contract WHERE contract_id IN (?, ?)
                """, frozenRunId, firstContractId, secondContractId);
        jdbc.update("UPDATE fgc.validation_run SET status='RUNNING', current_step=5, started_at=now() WHERE validation_run_id=?", frozenRunId);
        jdbc.update("UPDATE fgc.validation_run SET status='COMPLETED', current_step=8, completed_at=now() WHERE validation_run_id=?", frozenRunId);
        jdbc.update("UPDATE fgc.validation_run SET status='FINALIZED', current_step=10, finalized_at=now() WHERE validation_run_id=?", frozenRunId);
        var resultBefore = jdbc.queryForMap(
                "SELECT * FROM fgc.arbitrage_check WHERE arbitrage_check_id=?", original.getArbitrageCheckId());
        var evidenceBefore = jdbc.queryForList(
                "SELECT * FROM fgc.exception_occurrence WHERE validation_run_id=? ORDER BY exception_occurrence_id", frozenRunId);
        assertThat(evidenceBefore).isNotEmpty();

        assertThatThrownBy(() -> itemService.process(frozenRunId, firstContractId, asOfDate))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("Results of finalized validation run");
        assertThatThrownBy(() -> itemService.process(frozenRunId, secondContractId, asOfDate))
                .isInstanceOf(DataAccessException.class)
                .hasStackTraceContaining("Results of finalized validation run");
        Long userId = jdbc.queryForObject("SELECT user_id FROM fgc.app_user ORDER BY user_id LIMIT 1", Long.class);
        var context = new ValidationStepContext(frozenRunId,
                new ValidationJobContext(month, 1, ValidationRunType.PRE_CONFIRM, userId, "finalized-write-guard"));
        // 확정은 실행 전체의 상태이므로 DB 오류를 계약별 복구 가능한 skip으로 삼키지 않는다.
        assertThatThrownBy(() -> adapter.check(context)).isInstanceOf(DataAccessException.class);
        assertThat(jdbc.queryForMap("SELECT * FROM fgc.arbitrage_check WHERE arbitrage_check_id=?",
                original.getArbitrageCheckId())).isEqualTo(resultBefore);
        assertThat(jdbc.queryForList("SELECT * FROM fgc.exception_occurrence WHERE validation_run_id=? ORDER BY exception_occurrence_id",
                frozenRunId)).isEqualTo(evidenceBefore);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.arbitrage_check WHERE validation_run_id=?",
                Long.class, frozenRunId)).isEqualTo(1L);

        Long activeRunId = insertRun(month);
        assertThat(itemService.process(activeRunId, secondContractId, asOfDate).getArbitrageCheckId()).isNotNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fgc.arbitrage_check WHERE validation_run_id=?",
                Long.class, activeRunId)).isEqualTo(1L);
    }

    private Long insertRun(LocalDate month) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'PRE_CONFIRM', 'CREATED'
                  FROM fgc.validation_run WHERE validation_month = ? RETURNING validation_run_id
                """, Long.class, month, month);
    }

    private ArbitrageCheckInsertDTO row(Long runId, Long contractId, LocalDate date, String writer) {
        return ArbitrageCheckInsertDTO.builder().validationRunId(runId).contractId(contractId)
                .paymentStage(PaymentStage.GA_TO_FC).asOfDate(date).contractMonthNo(12)
                .cumulativePaidPremium(new BigDecimal("1200000")).paidCommissionAmount(BigDecimal.ZERO)
                .plannedCommissionAmount(BigDecimal.ZERO).includedSurrenderValueAmount(BigDecimal.ZERO)
                .refundAdditionAppliedYn(false).surrenderValueSourceType(SurrenderValueSourceType.NOT_APPLICABLE)
                .netDifferenceAmount(BigDecimal.ZERO).standardDeduction80Yn(false)
                .resultStatus(ArbitrageCheckStatus.CLEAR).calculationSnapshot("{\"writer\":\"" + writer + "\"}").build();
    }

    private boolean waitUntilBlocked(int holderPid, int waiterPid) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            if (Boolean.TRUE.equals(jdbc.queryForObject(
                    "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holderPid, waiterPid))) {
                return true;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return false;
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Concurrent UPSERT test interrupted", exception);
        }
    }
}
