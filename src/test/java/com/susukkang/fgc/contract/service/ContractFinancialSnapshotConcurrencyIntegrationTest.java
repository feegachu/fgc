package com.susukkang.fgc.contract.service;

import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.PaymentCycleCode;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.ContractFinancialSnapshotRepository;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 실제 PostgreSQL 계약 잠금 대기와 커밋 후 버전 재조회를 검증한다. */
@SpringBootTest(properties = {
        "fgc.batch.daily-changed-contract.enabled=false",
        "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_snapshot_concurrency?currentSchema=fgc"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Timeout(45)
class ContractFinancialSnapshotConcurrencyIntegrationTest {
    @Autowired private ContractService contracts;
    @Autowired private InsuranceContractRepository contractRepository;
    @Autowired private ContractFinancialSnapshotRepository snapshots;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void waitingCorrectionReadsCommittedVersionAndStaleEditCannotOverwriteIt(boolean staleEdit) throws Exception {
        Long id = newContract();
        InsuranceContract original = contractRepository.findById(id).orElseThrow();
        ContractUpdateRequest first = update(original, "150000");
        ContractUpdateRequest stale = update(original, "200000");
        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        AtomicInteger waiterPid = new AtomicInteger();
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<?> holder = workers.submit(() -> tx().executeWithoutResult(status -> {
                holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                contracts.updateContract(id, first);
                written.countDown();
                await(commit);
            }));
            await(written);
            // 원자성: 커밋 전 다른 세션은 기존 계약과 스냅샷을 함께 읽는다.
            assertThat(jdbc.queryForObject("SELECT first_premium_amount FROM fgc.insurance_contract WHERE contract_id=?", BigDecimal.class, id)).isEqualByComparingTo("100000");
            assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.contract_financial_snapshot WHERE contract_id=?", Integer.class, id)).isEqualTo(1);
            Future<Integer> waiter = workers.submit(() -> tx().execute(status -> {
                waiterPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                if (!staleEdit) return snapshots.appendManualInitialIfChanged(id);
                try {
                    contracts.updateContract(id, stale);
                    throw new AssertionError("오래된 요청은 거절되어야 한다");
                } catch (FgcBusinessException rejection) {
                    assertThat(rejection.getErrorCode()).isEqualTo(FgcErrorCode.CONT_003);
                    status.setRollbackOnly();
                    return -1;
                }
            }));
            boolean blocked = false;
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < deadline && !waiter.isDone()) {
                if (waiterPid.get() != 0 && Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holderPid.get(), waiterPid.get()))) {
                    blocked = true;
                    break;
                }
                Thread.sleep(20);
            }
            assertThat(blocked).as("선행 계약·정정 버전 커밋까지 PostgreSQL에서 대기").isTrue();
            commit.countDown();
            holder.get(20, TimeUnit.SECONDS);
            assertThat(waiter.get(20, TimeUnit.SECONDS)).isEqualTo(staleEdit ? -1 : 0);
            assertThat(jdbc.queryForObject("SELECT first_premium_amount FROM fgc.insurance_contract WHERE contract_id=?", BigDecimal.class, id)).isEqualByComparingTo("150000");
            assertThat(jdbc.queryForList("SELECT snapshot_version FROM fgc.contract_financial_snapshot WHERE contract_id=? ORDER BY snapshot_version", Integer.class, id)).containsExactly(1, 2);
            assertThat(jdbc.queryForObject("SELECT cumulative_paid_premium FROM fgc.contract_financial_snapshot WHERE contract_id=? AND snapshot_version=2", BigDecimal.class, id)).isEqualByComparingTo("150000");
        } finally {
            commit.countDown();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private TransactionTemplate tx() { return new TransactionTemplate(transactionManager); }
    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(20, TimeUnit.SECONDS)) throw new AssertionError("잠금 실행 경계 대기 시간 초과");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    private Long newContract() {
        InsuranceContract seed = contractRepository.findById(jdbc.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no='FGC-FGL01-202607-0001'", Long.class)).orElseThrow();
        return contracts.createContract(new ContractCreateRequest(seed.getInsurerId(), "SNAP-RACE-" + UUID.randomUUID(),
                seed.getProductOfferingId(), seed.getContractDate(), ContractStatus.ACTIVE,
                seed.getAgentId(), seed.getOrganizationId(), PaymentCycleCode.MONTHLY,
                new BigDecimal("100000"), new BigDecimal("100000"), new BigDecimal("100000"),
                120, BigDecimal.ZERO)).contractId();
    }

    private ContractUpdateRequest update(InsuranceContract source, String amount) {
        BigDecimal value = new BigDecimal(amount);
        return new ContractUpdateRequest(source.getContractNo(), source.getInsurerId(), source.getProductOfferingId(),
                source.getContractDate(), source.getCurrentStatus(), source.getAgentId(), source.getOrganizationId(),
                source.getPaymentCycleCode(), value, value, value, source.getPaymentTermMonths(),
                source.getStandardSurrenderDeductionAmount(), source.getUpdatedAt(), null);
    }
}
