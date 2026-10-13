package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.cap.dto.*;
import com.susukkang.fgc.cap.repository.CapScheduleAmountQueryRepository;
import com.susukkang.fgc.cap.repository.CapCheckQueryRepository;
import com.susukkang.fgc.cap.service.CapCalculator;
import com.susukkang.fgc.cap.service.CapCheckBatchItemService;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import com.susukkang.fgc.contract.repository.ContractQueryRepository;
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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;
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
@AutoConfigureMockMvc
@WithMockUser(roles = "SETTLEMENT")
@Timeout(45)
class ContractConsistencyIntegrationTest {
    @Autowired private ContractService contracts;
    @Autowired private InsuranceContractRepository contractRepository;
    @Autowired private ContractQueryRepository contractQueries;
    @Autowired private CapCheckQueryRepository capQueries;
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
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
    @MockitoSpyBean private com.susukkang.fgc.cap.repository.CapRuleQueryRepository capRules;
    @MockitoSpyBean private com.susukkang.fgc.contract.repository.ContractLockRepository contractLocks;

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
    void FGC_FUN_035_excludedCandidateDoesNotInflateSavedIncludedBreakdown(boolean existingPayment) {
        Long id = newContract();
        deactivateSchedules(id);
        BigDecimal existing = existingPayment ? new BigDecimal("600000") : BigDecimal.ZERO;
        if (existingPayment) payments.confirm(payment(id, existing, List.of(attribution(id, existing))), null);
        registerExcludedItemPolicy(id);
        List<CommissionPaymentAttributionRequest> excluded = List.of("300000", "400000").stream()
                .map(amount -> new CommissionPaymentAttributionRequest(id, LocalDate.of(2026, 7, 31),
                        new BigDecimal(amount), InclusionDecisionStatus.EXCLUDED, ExclusionType.VOICE_RECORDING,
                        "녹취 지원 제외 증빙", "DIRECT", "VOICE-EVIDENCE", AttributionMethod.DIRECT))
                .toList();
        Long payment = payment(id, new BigDecimal("700000"), excluded, "CONS_EXCLUDED");
        TransactionPrecheckResponse preview = payments.precheck(payment);
        assertThat(preview.confirmable()).isTrue();
        assertThat(preview.capPreview()).hasSize(1).allSatisfy(row -> {
            assertThat(row.candidateAmount()).isZero();
            assertThat(row.includedAmount()).isEqualTo(existing.longValueExact());
        });

        CommissionPaymentResponse confirmed = payments.confirm(payment, "EXCLUDED-" + payment);
        assertThat(confirmed.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(confirmed.capCheckIds()).hasSize(1);
        Long check = confirmed.capCheckIds().getFirst();
        BigDecimal included = jdbc.queryForObject("SELECT included_amount FROM fgc.cap_check WHERE cap_check_id=?",
                BigDecimal.class, check);
        assertThat(included).isEqualByComparingTo(existing);
        assertThat(jdbc.queryForObject("SELECT COALESCE(sum(amount),0) FROM fgc.cap_check_detail WHERE cap_check_id=?",
                BigDecimal.class, check)).isEqualByComparingTo(included);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check_detail WHERE cap_check_id=?",
                Integer.class, check)).isEqualTo(existingPayment ? 1 : 0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check_detail WHERE cap_check_id=? AND classification_snapshot<>'INCLUDED'",
                Integer.class, check)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check_detail WHERE cap_check_id=? AND transaction_attribution_id IN (SELECT transaction_attribution_id FROM fgc.transaction_attribution WHERE commission_transaction_id=?)",
                Integer.class, check, payment)).isZero();
        assertThat(jdbc.queryForObject("SELECT jsonb_array_length(calculation_snapshot->'candidateAttributions') FROM fgc.cap_check WHERE cap_check_id=?",
                Integer.class, check)).isEqualTo(2);
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
        // #482: 시행 전 계약은 비대상이다. 적용대상 계약의 룰셋 조회 실패로 CAP_004를 재현한다.
        org.mockito.Mockito.doReturn(null).when(capRules).findApplicableRuleSet(eq("GA_TO_FC"), any(), any(), any(), any());
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
            assertThat(jdbc.queryForObject("SELECT sum(amount) FROM fgc.cap_check_detail WHERE cap_check_id=?",
                    BigDecimal.class, check)).isEqualByComparingTo("40000");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check_detail WHERE cap_check_id=? AND amount<0",
                    Integer.class, check)).isZero();
            assertThat(jdbc.queryForObject("SELECT jsonb_array_length(calculation_snapshot->'candidateAttributions') FROM fgc.cap_check WHERE cap_check_id=?",
                    Integer.class, check)).isEqualTo(2);
            assertThat(jdbc.queryForObject("SELECT sum((candidate->>'signedAmount')::numeric) FROM fgc.cap_check cc CROSS JOIN LATERAL jsonb_array_elements(cc.calculation_snapshot->'candidateAttributions') candidate WHERE cc.cap_check_id=?",
                    BigDecimal.class, check)).isEqualByComparingTo("-60000");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void staleConcurrentUpdateIsRejectedForSameAndDifferentFields(boolean sameField) throws Exception {
        Long id = newContract();
        String originalNo = contractRepository.findById(id).orElseThrow().getContractNo();
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch committed = new CountDownLatch(1);
        ExecutorService workers = workers();
        try {
            Future<?> later = workers.submit(() -> transaction().executeWithoutResult(status -> {
                InsuranceContract stale = contractRepository.findById(id).orElseThrow();
                ContractUpdateRequest request = update(stale, sameField ? "300000" : "100000");
                if (!sameField) request.setContractNo(stale.getContractNo() + "-EDIT");
                read.countDown();
                await(committed);
                assertThatThrownBy(() -> contracts.updateContract(id, request))
                        .isInstanceOfSatisfying(FgcBusinessException.class, failure ->
                                assertThat(failure.getErrorCode()).isEqualTo(
                                        com.susukkang.fgc.common.exception.FgcErrorCode.CONT_003));
                status.setRollbackOnly();
            }));
            await(read);
            InsuranceContract before = contractRepository.findById(id).orElseThrow();
            contracts.updateContract(id, update(before, "200000"));
            int headers = jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_header WHERE contract_id=?", Integer.class, id);
            int checks = jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check WHERE contract_id=?", Integer.class, id);
            committed.countDown();
            later.get(20, TimeUnit.SECONDS);
            assertThat(contractRepository.findById(id).orElseThrow().getMonthlyEquivalentFirstPremium())
                    .isEqualByComparingTo("200000");
            assertThat(contractRepository.findById(id).orElseThrow().getContractNo()).isEqualTo(originalNo);
            assertActiveScheduleBasis(id, "200000");
            assertThat(capChecks.findLatest(id, PaymentStage.GA_TO_FC).orElseThrow().result().basePremiumAmount())
                    .isEqualByComparingTo("200000");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.schedule_header WHERE contract_id=?", Integer.class, id)).isEqualTo(headers);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.cap_check WHERE contract_id=?", Integer.class, id)).isEqualTo(checks);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.audit_log WHERE entity_type='CONTRACT' AND entity_id=? AND action_code='CONTRACT_UPDATED'", Integer.class, String.valueOf(id))).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT (before_value->>'firstPremiumAmount')::numeric FROM fgc.audit_log WHERE entity_type='CONTRACT' AND entity_id=? AND action_code='CONTRACT_UPDATED' ORDER BY audit_log_id DESC LIMIT 1",
                    BigDecimal.class, String.valueOf(id))).isEqualByComparingTo("100000");
            assertThat(jdbc.queryForObject("SELECT before_value->>'contractNo' FROM fgc.audit_log WHERE entity_type='CONTRACT' AND entity_id=? AND action_code='CONTRACT_UPDATED' ORDER BY audit_log_id DESC LIMIT 1",
                    String.class, String.valueOf(id))).isEqualTo(originalNo);
            // 상세 재조회 토큰으로 다시 요청하면 정상 저장되고 감사 before는 직전 커밋을 가리킨다.
            ContractUpdateRequest fresh = update(contractRepository.findById(id).orElseThrow(), "200000");
            fresh.setExpectedUpdatedAt(contractQueries.findDetailById(id).orElseThrow().getUpdatedAt());
            fresh.setContractNo(originalNo + "-FRESH");
            contracts.updateContract(id, fresh);
            assertThat(jdbc.queryForObject("SELECT (before_value->>'firstPremiumAmount')::numeric FROM fgc.audit_log WHERE entity_type='CONTRACT' AND entity_id=? AND action_code='CONTRACT_UPDATED' ORDER BY audit_log_id DESC LIMIT 1",
                    BigDecimal.class, String.valueOf(id))).isEqualByComparingTo("200000");
            assertThat(jdbc.queryForObject("SELECT before_value->>'contractNo' FROM fgc.audit_log WHERE entity_type='CONTRACT' AND entity_id=? AND action_code='CONTRACT_UPDATED' ORDER BY audit_log_id DESC LIMIT 1",
                    String.class, String.valueOf(id))).isEqualTo(originalNo);
            assertActiveScheduleBasis(id, "200000");
            assertThat(capChecks.findLatest(id, PaymentStage.GA_TO_FC).orElseThrow().result().basePremiumAmount())
                    .isEqualByComparingTo("200000");
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

    @Test
    void staleApiPutReturns409AndFreshDetailTokenCanBeSaved() throws Exception {
        Long id = newContract();
        String detail = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/api/v1/contracts/{id}", id))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString();
        ContractUpdateRequest stale = update(contractRepository.findById(id).orElseThrow(), "100000");
        stale.setExpectedUpdatedAt(java.time.OffsetDateTime.parse(objectMapper.readTree(detail).path("data").path("updatedAt").asText()));
        stale.setContractNo(stale.getContractNo() + "-STALE");
        contracts.updateContract(id, update(contractRepository.findById(id).orElseThrow(), "200000"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/contracts/{id}", id)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(stale)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.error.code").value("FGC-CONT-003"));
        ContractUpdateRequest fresh = update(contractRepository.findById(id).orElseThrow(), "200000");
        fresh.setExpectedUpdatedAt(contractQueries.findDetailById(id).orElseThrow().getUpdatedAt()
                .withOffsetSameInstant(java.time.ZoneOffset.ofHours(9)));
        fresh.setContractNo(fresh.getContractNo() + "-FRESH");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/v1/contracts/{id}", id)
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(fresh)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
        assertActiveScheduleBasis(id, "200000");
        assertThat(capChecks.findLatest(id, PaymentStage.GA_TO_FC).orElseThrow().result().basePremiumAmount())
                .isEqualByComparingTo("200000");
        assertThat(contractRepository.findById(id).orElseThrow().getContractNo()).isEqualTo(fresh.getContractNo());
    }

    @ParameterizedTest
    @EnumSource(value = CapCheckKind.class, names = {"MANUAL", "MONTHLY"})
    void insurerEvidenceIsRereadAfterWaitingForARealConfirmationCommit(CapCheckKind kind) throws Exception {
        Long id = newContract();
        // 3% 공제가 시행되는 계약을 고립된 fixture로 구성한다. 화면의 미래 계약일 제한과는 별개다.
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date=DATE '2027-03-02' WHERE contract_id=?", id);
        deactivateSchedules(id);
        insertComplianceEvidence(id, "100", true);
        Long draft = insertComplianceEvidence(id, "500", false);
        BigDecimal staleAmount = capQueries.selectComplianceEvidenceAmount(id, PaymentStage.INSURER_TO_GA);
        assertThat(staleAmount).isEqualByComparingTo("100");
        CapCalculationCommand stale = new CapCalculationCommand(id, PaymentStage.INSURER_TO_GA,
                LocalDate.of(2027, 3, 31), kind, null, staleAmount);
        CountDownLatch updated = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        AtomicInteger calculatorPid = new AtomicInteger();
        transaction().executeWithoutResult(status -> doAnswer(call -> {
            if (Thread.currentThread().getName().equals("consistency-evidence-cap")) {
                calculatorPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
            }
            return call.callRealMethod();
        }).when(contractLocks).lock(id));
        ExecutorService workers = workers();
        try {
            Future<?> confirmation = workers.submit(() -> transaction().executeWithoutResult(status -> {
                holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                // 지급 확정과 동일한 지급 → 계약 잠금 순서. 값은 mock 없이 DB guard로 확정한다.
                paymentQueries.findConfirmationDataForUpdate(draft);
                paymentQueries.lockAttributedContracts(draft);
                jdbc.update("UPDATE fgc.commission_transaction SET status='CONFIRMED' WHERE commission_transaction_id=?", draft);
                updated.countDown();
                await(commit);
            }));
            await(updated);
            Future<CapCheckSaveResult> calculation = workers.submit(() -> {
                Thread.currentThread().setName("consistency-evidence-cap");
                return kind == CapCheckKind.MONTHLY ? batchItem.process(stale) : capChecks.calculateAndSave(stale);
            });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            boolean blocked = false;
            while (System.nanoTime() < deadline) {
                if (calculatorPid.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holderPid.get(), calculatorPid.get()))) {
                    blocked = true;
                    break;
                }
                if (calculation.isDone()) break;
                Thread.sleep(20);
            }
            assertThat(blocked).as("증빙 확정이 커밋할 때까지 실제 PostgreSQL 계약 잠금에서 기다려야 한다").isTrue();
            commit.countDown();
            confirmation.get(20, TimeUnit.SECONDS);
            CapCheckSaveResult saved = calculation.get(20, TimeUnit.SECONDS);
            assertThat(saved.result().complianceDeductionAmount()).isEqualByComparingTo("600");
            // #468: 준법경영비를 산입액에서 제외하므로 실질 한도는 월납×12 + 확정 증빙액이다.
            assertThat(saved.result().limitAmount()).isEqualByComparingTo("1200600");
            assertThat(jdbc.queryForObject("SELECT (calculation_snapshot->>'complianceEvidenceAmount')::numeric FROM fgc.cap_check WHERE cap_check_id=?",
                    BigDecimal.class, saved.capCheckId())).isEqualByComparingTo("600");
            assertThat(jdbc.queryForObject("SELECT compliance_deduction_amount FROM fgc.cap_check WHERE cap_check_id=?",
                    BigDecimal.class, saved.capCheckId())).isEqualByComparingTo("600");
        } finally {
            commit.countDown();
            workers.shutdownNow();
        }
    }

    private Long insertComplianceEvidence(Long id, String amount, boolean confirmed) {
        Long payment = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction(payment_stage, source_type, source_business_key,
                    commission_item_id, policy_version_id, settlement_month, due_date, amount, cashflow_type, status)
                SELECT 'INSURER_TO_GA', 'GA_MANUAL_PAYMENT', ?, ci.commission_item_id, pv.policy_version_id,
                    DATE '2027-03-01', DATE '2027-03-31', ?, 'PAYMENT', 'DRAFT'
                  FROM fgc.commission_item ci CROSS JOIN fgc.policy_version pv
                 WHERE ci.item_code='BASE_COMMISSION' AND pv.policy_code='INS-CUR-2026-V1'
                RETURNING commission_transaction_id
                """, Long.class, "CONS-EVIDENCE-" + UUID.randomUUID(), new BigDecimal(amount));
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id, contract_id, attribution_date,
                    attribution_month, attributed_amount, inclusion_status_snapshot, exclusion_type_snapshot,
                    attribution_method, evidence_ref)
                VALUES (?, ?, DATE '2027-03-31', DATE '2027-03-01', ?, 'EXCLUDED', 'COMPLIANCE_3PCT', 'DIRECT', 'CONS-EVIDENCE')
                """, payment, id, new BigDecimal(amount));
        if (confirmed) jdbc.update("UPDATE fgc.commission_transaction SET status='CONFIRMED' WHERE commission_transaction_id=?", payment);
        return payment;
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

    private void registerExcludedItemPolicy(Long id) {
        Long originalRule = calculator.calculate(CapCalculationCommand.realtime(
                id, PaymentStage.GA_TO_FC, LocalDate.of(2026, 7, 31))).capRuleSetId();
        Long item = jdbc.queryForObject("""
                INSERT INTO fgc.commission_item(item_code,item_name,cashflow_type,item_category,effective_from)
                VALUES ('CONS_EXCLUDED','녹취 지원 검증 항목','PAYMENT','COST',DATE '2026-01-01')
                RETURNING commission_item_id
                """, Long.class);
        Long policy = jdbc.queryForObject("""
                INSERT INTO fgc.policy_version(policy_code,policy_name,policy_type,source_class,version_no,
                    effective_from,status,approval_evidence_ref)
                VALUES (?, '제외 귀속 상세 검증','CAP_1200','PROJECT_ASSUMPTION',1,
                    DATE '2026-01-01','DRAFT','CONS-EXCLUDED-POLICY') RETURNING policy_version_id
                """, Long.class, "CONS-EXCLUDED-" + UUID.randomUUID());
        Long rule = jdbc.queryForObject("""
                INSERT INTO fgc.cap_rule_set(policy_version_id,payment_stage,contract_date_from,contract_date_to,
                    insurer_id,product_group_code,channel_code,first_year_months,premium_multiplier,
                    compliance_deduction_pct,refund_addition_condition,warning_usage_pct)
                SELECT ?,payment_stage,contract_date_from,contract_date_to,insurer_id,product_group_code,
                    channel_code,first_year_months,premium_multiplier,compliance_deduction_pct,
                    refund_addition_condition,warning_usage_pct FROM fgc.cap_rule_set WHERE cap_rule_set_id=?
                RETURNING cap_rule_set_id
                """, Long.class, policy, originalRule);
        jdbc.update("""
                INSERT INTO fgc.cap_rule_item(cap_rule_set_id,commission_item_id,inclusion_status,
                    exclusion_type,evidence_required_yn,attribution_method,decision_reason)
                VALUES (?,?,'EXCLUDED','VOICE_RECORDING',true,'DIRECT','증빙 있는 녹취 지원 항목 제외')
                """, rule, item);
        jdbc.update("""
                UPDATE fgc.policy_version target SET status='APPROVED',approved_at=clock_timestamp(),
                    created_by=original.created_by,approved_by=original.approved_by
                FROM fgc.cap_rule_set crs JOIN fgc.policy_version original USING(policy_version_id)
                WHERE crs.cap_rule_set_id=? AND target.policy_version_id=?
                """, originalRule, policy);
        jdbc.update("UPDATE fgc.policy_version SET status='ACTIVE' WHERE policy_version_id=?", policy);
    }

    private ContractUpdateRequest update(InsuranceContract source, String premiumText) {
        BigDecimal premium = new BigDecimal(premiumText);
        return new ContractUpdateRequest(source.getContractNo(), source.getInsurerId(), source.getProductOfferingId(),
                source.getContractDate(), source.getCurrentStatus(), source.getAgentId(), source.getOrganizationId(),
                source.getPaymentCycleCode(), premium, premium, premium, source.getPaymentTermMonths(),
                source.getStandardSurrenderDeductionAmount(), source.getUpdatedAt());
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
