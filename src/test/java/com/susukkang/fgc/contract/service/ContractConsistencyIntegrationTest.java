package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.cap.dto.*;
import com.susukkang.fgc.cap.repository.CapScheduleAmountQueryRepository;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapCheckBatchItemService;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.schedule.repository.ScheduleWriteRepository;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.transaction.dto.*;
import com.susukkang.fgc.transaction.repository.CommissionPaymentQueryRepository;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/**
 * #431~#436: 실제 PostgreSQL 저장·잠금·서비스 경로의 정합성 회귀 검증.
 * Spy는 실행 경계에서 latch만 제어하며 실제 조회·계산·저장을 대체하지 않는다.
 * 동시성 사례는 고유한 신규 계약을 사용하여 시드 계약을 변경하지 않는다.
 */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_consistency?currentSchema=fgc"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@WithMockUser(roles = "SETTLEMENT")
@Timeout(45)
class ContractConsistencyIntegrationTest {
    @Autowired private ContractService contracts;
    @Autowired private InsuranceContractRepository contractRepository;
    @Autowired private ScheduleService schedules;
    @Autowired private CommissionPaymentService payments;
    @Autowired private CapCalculator calculator;
    @Autowired private CapCheckService capChecks;
    @Autowired private CapCheckBatchItemService batchItem;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private jakarta.persistence.EntityManager entityManager;
    @MockitoSpyBean private ScheduleWriteRepository scheduleWrites;
    @MockitoSpyBean private CommissionPaymentQueryRepository paymentQueries;
    @MockitoSpyBean private CapScheduleAmountQueryRepository capSchedules;

    @ParameterizedTest
    @CsvSource({"2,false,200000", "3,true,1", "7,true,1"})
    @Transactional
    void splitAttributionsCannotBypassTheContractLimit(int splits, boolean reverse, String excess) {
        Long id = newContract();
        deactivateSchedules(id);
        BigDecimal limit = calculator.calculate(CapCalculationCommand.realtime(
                id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 31))).limitAmount();
        if (splits == 2) assertThat(limit).isEqualByComparingTo("1200000");
        BigDecimal total = limit.add(new BigDecimal(excess));
        BigDecimal part = total.divideToIntegralValue(BigDecimal.valueOf(splits));
        List<CommissionPaymentAttributionRequest> rows = new java.util.ArrayList<>();
        for (int index = 0; index < splits; index++) {
            rows.add(attribution(id, index == splits - 1
                    ? total.subtract(part.multiply(BigDecimal.valueOf(splits - 1))) : part));
        }
        if (reverse) java.util.Collections.reverse(rows);
        Long payment = payment(id, total, rows);

        TransactionPrecheckResponse preview = payments.precheck(payment);
        assertThat(preview.confirmable()).isFalse();
        assertThat(preview.capPreview()).hasSize(1).allSatisfy(row -> {
            assertThat(row.includedAmount()).isEqualTo(total.longValueExact());
            assertThat(row.resultStatus()).isEqualTo(CapResultStatus.VIOLATION);
        });
        assertThatThrownBy(() -> payments.confirm(payment, null))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        failure -> assertThat(failure.getErrorCode()).isEqualTo(
                                com.susukkang.fgc.common.exception.FgcErrorCode.CAP_001));
        assertThat(jdbc.queryForObject("SELECT status FROM fgc.commission_transaction WHERE commission_transaction_id=?",
                String.class, payment)).isEqualTo("DRAFT");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check WHERE candidate_transaction_id=?",
                Integer.class, payment)).isEqualTo(1);
    }

    @Test
    @Transactional
    void exactLimitIncludesExistingPaymentsAndPersistsEveryCandidateDetail() {
        Long id = newContract();
        deactivateSchedules(id);
        BigDecimal limit = calculator.calculate(CapCalculationCommand.realtime(
                id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 31))).limitAmount();
        BigDecimal existing = new BigDecimal("100000");
        payments.confirm(payment(id, existing, List.of(attribution(id, existing))), null);
        BigDecimal remainder = limit.subtract(existing);
        BigDecimal first = remainder.divideToIntegralValue(BigDecimal.TWO);
        Long payment = payment(id, remainder,
                List.of(attribution(id, first), attribution(id, remainder.subtract(first))));
        assertThat(payments.precheck(payment).capPreview()).allSatisfy(row ->
                assertThat(row.includedAmount()).isEqualTo(limit.longValueExact()));

        CommissionPaymentResponse confirmed = payments.confirm(payment, "CONSISTENCY-" + payment);
        assertThat(confirmed.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(confirmed.capCheckIds()).hasSize(1);
        for (Long check : confirmed.capCheckIds()) {
            assertThat(jdbc.queryForObject("SELECT included_amount FROM fgc.cap_check WHERE cap_check_id=?",
                    BigDecimal.class, check)).isEqualByComparingTo(limit);
            assertThat(jdbc.queryForObject("SELECT sum(amount) FROM fgc.cap_check_detail WHERE cap_check_id=? AND classification_snapshot='INCLUDED'",
                    BigDecimal.class, check)).isEqualByComparingTo(limit);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check_detail WHERE cap_check_id=? AND transaction_attribution_id IN (SELECT transaction_attribution_id FROM fgc.transaction_attribution WHERE commission_transaction_id=?)",
                    Integer.class, check, payment)).isEqualTo(2);
        }
        assertThat(payments.confirm(payment, "CONSISTENCY-" + payment).capCheckIds())
                .containsExactlyElementsOf(confirmed.capCheckIds());
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 7})
    @Transactional
    void splitAttributionsSaveOneContractCheckAndEachDetailOnce(int splits) {
        Long id = newContract();
        deactivateSchedules(id);
        BigDecimal part = new BigDecimal("100000");
        List<CommissionPaymentAttributionRequest> rows = new java.util.ArrayList<>();
        for (int index = 0; index < splits; index++) rows.add(attribution(id, part));
        java.util.Collections.reverse(rows);
        Long payment = payment(id, part.multiply(BigDecimal.valueOf(splits)), rows);

        assertThat(payments.precheck(payment).capPreview()).hasSize(1);
        CommissionPaymentResponse confirmed = payments.confirm(payment, "SPLIT-" + payment);
        assertThat(confirmed.capCheckIds()).hasSize(1);
        Long check = confirmed.capCheckIds().getFirst();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check WHERE candidate_transaction_id=?",
                Integer.class, payment)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check_detail WHERE cap_check_id=?",
                Integer.class, check)).isEqualTo(splits);
        assertThat(jdbc.queryForObject("SELECT count(DISTINCT transaction_attribution_id) FROM fgc.cap_check_detail WHERE cap_check_id=?",
                Integer.class, check)).isEqualTo(splits);
        assertThat(payments.confirm(payment, "SPLIT-" + payment).capCheckIds()).containsExactly(check);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check WHERE candidate_transaction_id=?",
                Integer.class, payment)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Transactional
    void groupingDoesNotSkipAReviewRequiredAttribution(boolean reviewFirst) {
        Long id = newContract();
        deactivateSchedules(id);
        CommissionPaymentAttributionRequest review = new CommissionPaymentAttributionRequest(id,
                LocalDate.of(2026, 7, 31), new BigDecimal("100000"), InclusionDecisionStatus.REVIEW_REQUIRED,
                ExclusionType.NONE, "검토필요 귀속", "DIRECT", "REVIEW-EVIDENCE", AttributionMethod.DIRECT);
        CommissionPaymentAttributionRequest included = attribution(id, new BigDecimal("600000"));
        Long payment = payment(id, new BigDecimal("700000"),
                reviewFirst ? List.of(review, included) : List.of(included, review));
        TransactionPrecheckResponse preview = payments.precheck(payment);
        assertThat(preview.confirmable()).isFalse();
        assertThat(preview.capPreview()).hasSize(1).allSatisfy(row -> {
            assertThat(row.resultStatus()).isEqualTo(CapResultStatus.REVIEW_REQUIRED);
            assertThat(row.candidateAmount()).isEqualTo(600000);
        });
        assertThatThrownBy(() -> payments.confirm(payment, null)).isInstanceOfSatisfying(FgcBusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(
                        com.susukkang.fgc.common.exception.FgcErrorCode.CAP_002));
        assertThat(jdbc.queryForObject("SELECT status FROM fgc.commission_transaction WHERE commission_transaction_id=?",
                String.class, payment)).isEqualTo("DRAFT");
    }

    @Test
    @Transactional
    void differentContractsKeepSeparateLimits() {
        Long first = newContract();
        Long second = newContract();
        deactivateSchedules(first);
        deactivateSchedules(second);
        BigDecimal limit = calculator.calculate(CapCalculationCommand.realtime(
                first, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 31))).limitAmount();
        Long payment = payment(first, limit.multiply(BigDecimal.TWO),
                List.of(attribution(first, limit), attribution(second, limit)));
        TransactionPrecheckResponse preview = payments.precheck(payment);
        assertThat(preview.confirmable()).isTrue();
        assertThat(preview.capPreview()).hasSize(2);
        CommissionPaymentResponse confirmed = payments.confirm(payment, null);
        assertThat(confirmed.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(confirmed.capCheckIds()).hasSize(2);
    }

    @ParameterizedTest
    @CsvSource({"2026-07-10,2027-07-10", "2032-02-29,2033-02-28"})
    @Transactional
    void firstYearBoundaryExcludesOnlyTheRowsOnOrAfterMonthThirteen(LocalDate start, LocalDate anniversary) {
        Long id = newContract();
        deactivateSchedules(id);
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date=? WHERE contract_id=?", start, id);
        entityManager.refresh(contractRepository.findById(id).orElseThrow());
        LocalDate end = contractRepository.findById(id).orElseThrow().getContractDate().plusMonths(12);
        assertThat(end).isEqualTo(anniversary);
        BigDecimal limit = calculator.calculate(CapCalculationCommand.realtime(id, PaymentStage.GA_TO_FC, end)).limitAmount();
        CommissionPaymentAttributionRequest lastDay = new CommissionPaymentAttributionRequest(id, end.minusDays(1), limit,
                InclusionDecisionStatus.INCLUDED, ExclusionType.NONE, "초년도 마지막 날", "DIRECT", "BOUNDARY", AttributionMethod.DIRECT);
        CommissionPaymentAttributionRequest nextYear = new CommissionPaymentAttributionRequest(id, end, limit.multiply(BigDecimal.TWO),
                InclusionDecisionStatus.INCLUDED, ExclusionType.NONE, "13차월 첫날", "DIRECT", "BOUNDARY", AttributionMethod.DIRECT);
        Long payment = payment(id, limit.multiply(new BigDecimal("3")), List.of(nextYear, lastDay));
        assertThat(payments.precheck(payment).capPreview()).allSatisfy(row ->
                assertThat(row.includedAmount()).isEqualTo(limit.longValueExact()));
        CommissionPaymentResponse confirmed = payments.confirm(payment, null);
        assertThat(confirmed.capCheckIds()).hasSize(1);
        for (Long check : confirmed.capCheckIds()) {
            assertThat(jdbc.queryForObject("SELECT sum(amount) FROM fgc.cap_check_detail WHERE cap_check_id=? AND classification_snapshot='INCLUDED'",
                    BigDecimal.class, check)).isEqualByComparingTo(limit);
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void missingCapRuleKeepsTheContractLockedUntilTheOuterTransactionEnds(boolean rollback) throws Exception {
        Long id = newContract();
        // 운영 룰셋 시행 전 계약을 만들어 실제 계산기에서 CAP_004를 발생시킨다.
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date='1900-01-01' WHERE contract_id=?", id);
        int checksBefore = jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check WHERE contract_id=?", Integer.class, id);
        CountDownLatch reviewWritten = new CountDownLatch(1);
        CountDownLatch finishTransaction = new CountDownLatch(1);
        CountDownLatch writerStarted = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        AtomicInteger writerPid = new AtomicInteger();
        ExecutorService workers = workers();
        try {
            Future<?> holder = workers.submit(() -> transaction().executeWithoutResult(status -> {
                holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                assertThatThrownBy(() -> capChecks.calculateAndSave(CapCalculationCommand.realtime(
                        id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 31))))
                        .isInstanceOfSatisfying(FgcBusinessException.class,
                                failure -> assertThat(failure.getErrorCode()).isEqualTo(
                                        com.susukkang.fgc.common.exception.FgcErrorCode.CAP_004));
                assertThat(status.isRollbackOnly()).isFalse();
                schedules.registerCapRuleReview(id, PaymentStage.GA_TO_FC, "CAP_004 lock regression");
                reviewWritten.countDown();
                await(finishTransaction);
                if (rollback) status.setRollbackOnly();
            }));
            await(reviewWritten);
            Future<?> writer = workers.submit(() -> transaction().executeWithoutResult(status -> {
                writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                writerStarted.countDown();
                jdbc.update("UPDATE fgc.insurance_contract SET contract_no=contract_no || '-LOCKED' WHERE contract_id=?", id);
            }));
            await(writerStarted);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean blockedByHolder = false;
            while (System.nanoTime() < deadline && !writer.isDone()) {
                if (Boolean.TRUE.equals(jdbc.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))",
                        Boolean.class, holderPid.get(), writerPid.get()))) {
                    blockedByHolder = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(blockedByHolder).as("CAP_004 이후에도 상위 트랜잭션이 경쟁 수정을 막아야 한다").isTrue();
            assertThat(writer.isDone()).isFalse();
            finishTransaction.countDown();
            holder.get(20, TimeUnit.SECONDS);
            writer.get(20, TimeUnit.SECONDS);
            assertThat(jdbc.queryForObject("SELECT contract_no FROM fgc.insurance_contract WHERE contract_id=?", String.class, id))
                    .endsWith("-LOCKED");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.exception_case WHERE contract_id=? AND description='CAP_004 lock regression'",
                    Integer.class, id)).isEqualTo(rollback ? 0 : 1);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check WHERE contract_id=?", Integer.class, id))
                    .isEqualTo(checksBefore);
        } finally {
            finishTransaction.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    @Transactional
    void splitDeductionsReduceActualIncludedAmountAndKeepTheSavedBreakdownValid() {
        Long id = newContract();
        deactivateSchedules(id);
        payments.confirm(payment(id, new BigDecimal("100000"), List.of(attribution(id, new BigDecimal("100000")))), null);
        Long payment = payment(id, new BigDecimal("60000"), List.of(
                attribution(id, new BigDecimal("30000")), attribution(id, new BigDecimal("30000"))), "CLAWBACK");
        assertThat(payments.precheck(payment).capPreview()).allSatisfy(row -> {
            assertThat(row.candidateAmount()).isEqualTo(-60000);
            assertThat(row.includedAmount()).isEqualTo(40000);
        });
        CommissionPaymentResponse confirmed = payments.confirm(payment, null);
        for (Long check : confirmed.capCheckIds()) {
            assertThat(jdbc.queryForObject("SELECT sum(amount) FROM fgc.cap_check_detail WHERE cap_check_id=? AND classification_snapshot='INCLUDED'",
                    BigDecimal.class, check)).isEqualByComparingTo("40000");
            assertThat(jdbc.queryForObject("SELECT jsonb_array_length(calculation_snapshot->'candidateAttributions') FROM fgc.cap_check WHERE cap_check_id=?",
                    Integer.class, check)).isEqualTo(2);
        }
    }

    @Test
    void cachedConcurrentUpdateReevaluatesScheduleChangesFromCommittedState() throws Exception {
        Long id = newContract();
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        ExecutorService workers = workers();
        try {
            Future<?> later = workers.submit(() -> transaction().executeWithoutResult(status -> {
                InsuranceContract stale = contractRepository.findById(id).orElseThrow();
                ContractUpdateRequest request = update(stale, "100000");
                request.setContractNo(stale.getContractNo() + "-EDIT");
                read.countDown();
                await(committed);
                contracts.updateContract(id, request);
            }));
            await(read);
            InsuranceContract before = contractRepository.findById(id).orElseThrow();
            contracts.updateContract(id, update(before, "200000"));
            committed.countDown();
            later.get(20, TimeUnit.SECONDS);
            assertThat(contractRepository.findById(id).orElseThrow().getMonthlyEquivalentFirstPremium())
                    .isEqualByComparingTo("100000");
            assertActiveScheduleBasis(id, "100000");
            assertThat(jdbc.queryForObject("SELECT (before_value->>'firstPremiumAmount')::numeric FROM fgc.audit_log WHERE entity_type='CONTRACT' AND entity_id=? AND action_code='CONTRACT_UPDATED' ORDER BY audit_log_id DESC LIMIT 1",
                    BigDecimal.class, String.valueOf(id))).isEqualByComparingTo("200000");
        } finally {
            committed.countDown();
            workers.shutdownNow();
        }
    }

    @Test
    void generationUsesContractAndPoliciesReadAfterTheContractLock() throws Exception {
        Long id = newContract();
        CountDownLatch beforeLock = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        transaction().executeWithoutResult(status -> doAnswer(call -> {
            if (Thread.currentThread().getName().equals("consistency-generator")) {
                beforeLock.countDown();
                await(committed);
            }
            return call.callRealMethod();
        }).when(scheduleWrites).lockContractForScheduleGeneration(id));
        ExecutorService workers = workers();
        try {
            Future<?> generation = workers.submit(() -> {
                Thread.currentThread().setName("consistency-generator");
                return schedules.generateSchedulesByContractId(id);
            });
            await(beforeLock);
            transaction().executeWithoutResult(status -> {
                contracts.updateContract(id, update(contractRepository.findById(id).orElseThrow(), "200000"));
                // 누락 단계의 재생성을 강제한다. 다른 단계는 정상 활성 상태를 유지한다.
                jdbc.update("UPDATE fgc.schedule_header SET active_yn=false WHERE contract_id=? AND payment_stage='GA_TO_FC' AND active_yn=true", id);
            });
            committed.countDown();
            generation.get(20, TimeUnit.SECONDS);
            assertActiveScheduleBasis(id, "200000");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_header WHERE contract_id=? AND active_yn=true AND schedule_purpose='OPERATIONAL'",
                    Integer.class, id)).isEqualTo(2);
        } finally {
            committed.countDown();
            workers.shutdownNow();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void confirmationValidatesTheContractDateCommittedWhileWaitingForTheLock(boolean becomesValid) throws Exception {
        Long id = newContract();
        Long payment = payment(id, BigDecimal.ZERO, List.of(attribution(id, BigDecimal.ZERO)));
        if (becomesValid) {
            jdbc.update("UPDATE fgc.insurance_contract SET contract_date=? WHERE contract_id=?",
                    LocalDate.of(2026, 8, 1), id);
        }
        CountDownLatch beforeLock = new CountDownLatch(1);
        doAnswer(call -> {
            beforeLock.countDown();
            return call.callRealMethod();
        }).when(paymentQueries).lockAttributedContracts(payment);
        ExecutorService workers = workers();
        try {
            java.util.concurrent.atomic.AtomicReference<Future<String>> confirmation = new java.util.concurrent.atomic.AtomicReference<>();
            transaction().executeWithoutResult(status -> {
                jdbc.update("UPDATE fgc.insurance_contract SET contract_date=? WHERE contract_id=?",
                        becomesValid ? LocalDate.of(2026, 7, 10) : LocalDate.of(2026, 8, 1), id);
                confirmation.set(workers.submit(() -> {
                    try {
                        payments.confirm(payment, null);
                        return "CONFIRMED";
                    } catch (FgcBusinessException failure) {
                        return failure.getErrorCode().name();
                    }
                }));
                await(beforeLock);
            });
            assertThat(confirmation.get().get(20, TimeUnit.SECONDS)).isEqualTo(becomesValid ? "CONFIRMED" : "TRAN_008");
            assertThat(jdbc.queryForObject("SELECT status FROM fgc.commission_transaction WHERE commission_transaction_id=?",
                    String.class, payment)).isEqualTo(becomesValid ? "CONFIRMED" : "DRAFT");
        } finally {
            workers.shutdownNow();
        }
    }

    @ParameterizedTest
    @EnumSource(value = CapCheckKind.class, names = {"MANUAL", "MONTHLY"})
    void savedCapInputsStayConsistentDuringConcurrentContractRegeneration(CapCheckKind kind) throws Exception {
        Long id = newContract();
        CapCalculationCommand command = new CapCalculationCommand(id, PaymentStage.GA_TO_FC,
                LocalDate.of(2026, 7, 31), kind, null);
        CapCalculationResult before = calculator.calculate(command);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch continueCalculation = new CountDownLatch(1);
        AtomicInteger writerPid = new AtomicInteger();
        doAnswer(call -> {
            if (Thread.currentThread().getName().equals("consistency-cap")) {
                read.countDown();
                await(continueCalculation);
            }
            return call.callRealMethod();
        }).when(capSchedules).findFirstYearScheduleAmounts(eq(id), eq("GA_TO_FC"), anyInt());
        ExecutorService workers = workers();
        try {
            Future<CapCheckSaveResult> calculation = workers.submit(() -> {
                Thread.currentThread().setName("consistency-cap");
                return kind == CapCheckKind.MONTHLY ? batchItem.process(command) : capChecks.calculateAndSave(command);
            });
            await(read);
            Future<?> update = workers.submit(() -> transaction().executeWithoutResult(status -> {
                writerPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                contracts.updateContract(id, update(contractRepository.findById(id).orElseThrow(), "200000"));
            }));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean observed = false;
            while (System.nanoTime() < deadline) {
                if (update.isDone() || writerPid.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT cardinality(pg_blocking_pids(?)) > 0", Boolean.class, writerPid.get()))) {
                    observed = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(observed).as("writer must either commit or wait on a real PostgreSQL lock").isTrue();
            continueCalculation.countDown();
            CapCheckSaveResult saved = calculation.get(20, TimeUnit.SECONDS);
            update.get(20, TimeUnit.SECONDS);
            assertThat(saved.result().basePremiumAmount()).isEqualByComparingTo(before.basePremiumAmount());
            assertThat(saved.result().includedAmount()).isEqualByComparingTo(before.includedAmount());
            assertThat(saved.result().details()).extracting(CapCheckDetailLine::scheduleLineId)
                    .containsExactlyElementsOf(before.details().stream().map(CapCheckDetailLine::scheduleLineId).toList());
            assertThat(capChecks.findLatest(id, PaymentStage.GA_TO_FC).orElseThrow().result().basePremiumAmount())
                    .isEqualByComparingTo("200000");
        } finally {
            continueCalculation.countDown();
            workers.shutdownNow();
        }
    }

    private Long newContract() {
        InsuranceContract seed = contractRepository.findById(jdbc.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no='FGC-FGL01-202607-0001'", Long.class)).orElseThrow();
        return contracts.createContract(new ContractCreateRequest(seed.getInsurerId(), "CONS-" + UUID.randomUUID(),
                seed.getProductOfferingId(), seed.getContractDate(), ContractStatus.ACTIVE,
                seed.getAgentId(), seed.getOrganizationId(), PaymentCycleCode.MONTHLY,
                new BigDecimal("100000"), new BigDecimal("100000"), new BigDecimal("100000"),
                120, BigDecimal.ZERO)).contractId();
    }

    private ContractUpdateRequest update(InsuranceContract source, String premiumText) {
        BigDecimal premium = new BigDecimal(premiumText);
        return new ContractUpdateRequest(source.getContractNo(), source.getInsurerId(), source.getProductOfferingId(),
                source.getContractDate(), source.getCurrentStatus(), source.getAgentId(), source.getOrganizationId(),
                source.getPaymentCycleCode(), premium, premium, premium, source.getPaymentTermMonths(),
                source.getStandardSurrenderDeductionAmount());
    }

    private CommissionPaymentAttributionRequest attribution(Long id, BigDecimal amount) {
        return new CommissionPaymentAttributionRequest(id, LocalDate.of(2026, 7, 31), amount,
                InclusionDecisionStatus.INCLUDED, ExclusionType.NONE, "정합성 통합 검증", "DIRECT", "CONS-EVIDENCE",
                AttributionMethod.DIRECT);
    }

    private Long payment(Long id, BigDecimal total, List<CommissionPaymentAttributionRequest> rows) {
        return payment(id, total, rows, "BASE_COMMISSION");
    }

    private Long payment(Long id, BigDecimal total, List<CommissionPaymentAttributionRequest> rows, String itemCode) {
        InsuranceContract contract = contractRepository.findById(id).orElseThrow();
        Long item = jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code=?", Long.class, itemCode);
        Long policy = jdbc.queryForObject("SELECT policy_version_id FROM fgc.policy_version WHERE policy_code='GA-CUR-2026-V1'", Long.class);
        return payments.create(new CommissionPaymentCreateRequest("GA_MANUAL_PAYMENT", "CONS-" + UUID.randomUUID(),
                id, contract.getAgentId(), item, total, LocalDate.of(2026, 7, 1),
                itemCode.equals("CLAWBACK") ? "DEDUCTION" : "PAYMENT", LocalDate.of(2026, 7, 31),
                PaymentStage.GA_TO_FC, policy, rows, "CONS-EVIDENCE", "#431~#436 regression")).paymentId();
    }

    private void deactivateSchedules(Long id) {
        jdbc.update("UPDATE fgc.schedule_header SET active_yn=false WHERE contract_id=?", id);
    }

    private void assertActiveScheduleBasis(Long id, String premium) {
        assertThat(jdbc.queryForList("SELECT sl.basis_amount FROM fgc.schedule_line sl JOIN fgc.schedule_header sh USING(schedule_header_id) WHERE sh.contract_id=? AND sh.active_yn=true AND sl.basis_code='MONTHLY_EQUIVALENT_FIRST_PREMIUM'",
                BigDecimal.class, id)).isNotEmpty().allSatisfy(amount -> assertThat(amount).isEqualByComparingTo(premium));
    }

    private TransactionTemplate transaction() { return new TransactionTemplate(transactionManager); }

    private ExecutorService workers() {
        return new DelegatingSecurityContextExecutorService(Executors.newFixedThreadPool(2));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new AssertionError("concurrency boundary timed out");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
