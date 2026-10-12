package com.susukkang.fgc.reconciliation;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.reconciliation.adapter.ReconciliationBatchAdapter;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.service.ReconciliationBatchRunService;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FGC-FUN-048-04: Step 7 대사 실행 준비의 동시 소유권과 자연키 멱등성을 PostgreSQL에서 검증한다.
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
    private ReconciliationBatchAdapter batchAdapter;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long validationRunId;

    @AfterEach
    void cleanUp() {
        if (validationRunId == null) {
            return;
        }
        jdbcTemplate.update("""
                DELETE FROM fgc.reconciliation_match
                 WHERE reconciliation_result_id IN (
                     SELECT result.reconciliation_result_id
                       FROM fgc.reconciliation_result result
                       JOIN fgc.reconciliation_run run
                         ON run.reconciliation_run_id = result.reconciliation_run_id
                      WHERE run.validation_run_id = ?)
                """, validationRunId);
        jdbcTemplate.update("""
                DELETE FROM fgc.reconciliation_result
                 WHERE reconciliation_run_id IN (
                     SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE validation_run_id = ?)
                """, validationRunId);
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

    /**
     * #516 선정 계약이 없는 보험사도 그 달 확정 지급이 있으면 대사한다.
     * 시드 R7(FGC-FGN01-202603-0001)은 7/15 해지 뒤 8/25 GA→FC 650,000원이 지급됐다(V4 정답표 :442).
     * 검증 대상 0건인 실행에서도 FGN01 대사가 돌아 INVALID_CONTRACT_PAYMENT로 나와야 한다.
     */
    @Test
    void 선정_계약이_없는_보험사의_해지_후_지급도_비유효_계약_지급으로_대사한다() {
        LocalDate august = LocalDate.of(2026, 8, 1);
        Long userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM fgc.app_user WHERE login_id = 'settle01'", Long.class);
        int runNo = Math.abs(UUID.randomUUID().hashCode() % 1_000_000) + 100_000;
        validationRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (
                    validation_month, run_no, run_type, triggered_by
                ) VALUES (?, ?, 'PRE_CONFIRM', ?)
                RETURNING validation_run_id
                """, Long.class, august, runNo, userId);
        jdbcTemplate.update("""
                UPDATE fgc.validation_run
                   SET status = 'RUNNING',
                       started_at = clock_timestamp()
                 WHERE validation_run_id = ?
                   AND status = 'CREATED'
                """, validationRunId);
        Long fgn01 = jdbcTemplate.queryForObject(
                "SELECT insurer_id FROM fgc.insurer WHERE insurer_code = 'FGN01'", Long.class);

        batchAdapter.reconcile(new ValidationStepContext(
                validationRunId,
                new ValidationJobContext(
                        august, runNo, ValidationRunType.PRE_CONFIRM,
                        userId, "IT-516-" + validationRunId)), PaymentStage.GA_TO_FC);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.validation_target WHERE validation_run_id = ?",
                Integer.class, validationRunId)).isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT status FROM fgc.reconciliation_run
                 WHERE validation_run_id = ? AND insurer_id = ? AND payment_stage = 'GA_TO_FC'
                """, String.class, validationRunId, fgn01)).isEqualTo("COMPLETED");
        Map<String, Object> r7 = jdbcTemplate.queryForMap("""
                SELECT result.primary_reason_code, result.expected_total_amount, result.actual_total_amount
                  FROM fgc.reconciliation_result result
                  JOIN fgc.reconciliation_run run
                    ON run.reconciliation_run_id = result.reconciliation_run_id
                  JOIN fgc.insurance_contract contract ON contract.contract_id = result.contract_id
                 WHERE run.validation_run_id = ?
                   AND contract.contract_no = 'FGC-FGN01-202603-0001'
                """, validationRunId);
        assertThat(r7.get("primary_reason_code")).isEqualTo("INVALID_CONTRACT_PAYMENT");
        assertThat((BigDecimal) r7.get("expected_total_amount")).isEqualByComparingTo("0");
        assertThat((BigDecimal) r7.get("actual_total_amount")).isEqualByComparingTo("650000");
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
