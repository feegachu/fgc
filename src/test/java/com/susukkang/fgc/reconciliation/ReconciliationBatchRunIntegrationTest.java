package com.susukkang.fgc.reconciliation;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.service.ReconciliationBatchRunService;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : Step 7 대사 실행 준비의 동시 소유권과 자연키 멱등성을 PostgreSQL에서 검증한다.
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@SpringBootTest
class ReconciliationBatchRunIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2097, 11, 1);

    @Autowired
    private ReconciliationBatchRunService batchRunService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long validationRunId;

    @AfterEach
    void cleanUp() {
        if (validationRunId == null) {
            return;
        }
        jdbcTemplate.update(
                "DELETE FROM fgc.reconciliation_run WHERE validation_run_id = ?", validationRunId);
        jdbcTemplate.update(
                "DELETE FROM fgc.validation_target WHERE validation_run_id = ?", validationRunId);
        jdbcTemplate.update(
                "DELETE FROM fgc.validation_run WHERE validation_run_id = ?", validationRunId);
    }

    @Test
    void 동일_검증실행을_동시에_준비해도_RUNNING_RUN은_한_작업만_소유한다() throws Exception {
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM fgc.app_user WHERE login_id = 'settle01'", Long.class);
        ContractTarget target = jdbcTemplate.queryForObject("""
                SELECT contract.contract_id, contract.product_offering_id, contract.insurer_id
                  FROM fgc.insurance_contract contract
                  JOIN fgc.insurer insurer ON insurer.insurer_id = contract.insurer_id
                 WHERE insurer.active_yn = TRUE
                 ORDER BY contract.contract_id
                 LIMIT 1
                """, (resultSet, rowNum) -> new ContractTarget(
                resultSet.getLong("contract_id"),
                resultSet.getLong("product_offering_id"),
                resultSet.getLong("insurer_id")));
        int runNo = Math.abs(UUID.randomUUID().hashCode() % 1_000_000) + 100_000;
        validationRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (
                    validation_month, run_no, run_type, triggered_by
                ) VALUES (?, ?, 'PRE_CONFIRM', ?)
                RETURNING validation_run_id
                """, Long.class, TEST_MONTH, runNo, userId);
        jdbcTemplate.update("""
                UPDATE fgc.validation_run
                   SET status = 'RUNNING',
                       started_at = clock_timestamp()
                 WHERE validation_run_id = ?
                   AND status = 'CREATED'
                """, validationRunId);
        jdbcTemplate.update("""
                INSERT INTO fgc.validation_target (
                    validation_run_id, contract_id, product_offering_id, selection_status
                ) VALUES (?, ?, ?, 'SELECTED')
                """, validationRunId, target.contractId(), target.productOfferingId());
        assertThat(batchRunService.findSelectedContractIds(validationRunId, target.insurerId()))
                .containsExactly(target.contractId());

        ValidationStepContext context = new ValidationStepContext(
                validationRunId,
                new ValidationJobContext(
                        TEST_MONTH, runNo, ValidationRunType.PRE_CONFIRM,
                        userId, "IT-048-04-CONCURRENT-" + validationRunId));
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            CompletableFuture<List<ReconciliationExecutionRequest>> first = prepareAsync(
                    executor, start, context);
            CompletableFuture<List<ReconciliationExecutionRequest>> second = prepareAsync(
                    executor, start, context);
            start.countDown();

            List<ReconciliationExecutionRequest> firstRequests = first.get(10, TimeUnit.SECONDS);
            List<ReconciliationExecutionRequest> secondRequests = second.get(10, TimeUnit.SECONDS);

            assertThat(firstRequests.size() + secondRequests.size()).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                      FROM fgc.reconciliation_run
                     WHERE validation_run_id = ?
                       AND settlement_month = ?
                       AND payment_stage = 'GA_TO_FC'
                       AND status = 'RUNNING'
                    """, Integer.class, validationRunId, TEST_MONTH)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    private CompletableFuture<List<ReconciliationExecutionRequest>> prepareAsync(
            ExecutorService executor,
            CountDownLatch start,
            ValidationStepContext context
    ) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                start.await();
                return batchRunService.prepare(context, PaymentStage.GA_TO_FC);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(exception);
            }
        }, executor);
    }

    private record ContractTarget(Long contractId, Long productOfferingId, Long insurerId) {
    }
}
