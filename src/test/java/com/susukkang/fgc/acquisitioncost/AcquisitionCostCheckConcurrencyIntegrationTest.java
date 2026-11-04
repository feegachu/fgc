package com.susukkang.fgc.acquisitioncost;

import com.susukkang.fgc.acquisitioncost.dto.AcquisitionCostCheckRequest;
import com.susukkang.fgc.acquisitioncost.service.AcquisitionCostCheckService;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 커밋이 필요한 동시성 시험이라 전용 DB를 쓴다. 데모 시드 계약의 결과 행만 쌓이고 업무 원천은 바꾸지 않는다. */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_acquisition_lock_651?currentSchema=fgc")
class AcquisitionCostCheckConcurrencyIntegrationTest {
    @Autowired private AcquisitionCostCheckService service;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private long contractId() {
        return jdbc.queryForObject("SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL01-202703-0001'", Long.class);
    }

    private long userId() {
        return jdbc.queryForObject("SELECT user_id FROM fgc.app_user WHERE login_id = 'settle01'", Long.class);
    }

    private long stored(long contractId) {
        return jdbc.queryForObject("SELECT count(*) FROM fgc.acquisition_cost_check WHERE contract_id = ?", Long.class, contractId);
    }

    @Test
    @Timeout(30)
    void sameKeyWhileProcessingIsRejectedAndAfterCommitReplaysOneRow() throws Exception {
        long contract = contractId();
        long before = stored(contract);
        var request = new AcquisitionCostCheckRequest(contract, PaymentStage.GA_TO_FC);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = Executors.newSingleThreadExecutor()) {
            var first = worker.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                var result = service.request(request, "same-key-651", userId());
                entered.countDown();
                try {
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return result;
            }));
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.request(request, "same-key-651", userId()))
                    .isInstanceOfSatisfying(FgcBusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_REQUEST_IN_PROGRESS));

            release.countDown();
            var committed = first.get(15, TimeUnit.SECONDS);
            assertThat(service.request(request, "same-key-651", userId()).acquisitionCostCheckId())
                    .isEqualTo(committed.acquisitionCostCheckId());
        }
        assertThat(stored(contract)).isEqualTo(before + 1);
    }

    @Test
    @Timeout(30)
    void otherKeyWaitsOnTheContractLockAndAppendsItsOwnRow() throws Exception {
        long contract = contractId();
        long before = stored(contract);
        var request = new AcquisitionCostCheckRequest(contract, PaymentStage.GA_TO_FC);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                var result = service.request(request, "key-a-651", userId());
                entered.countDown();
                try {
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException e) {
                    throw new IllegalStateException(e);
                }
                return result;
            }));
            assertThat(entered.await(15, TimeUnit.SECONDS)).isTrue();
            var second = workers.submit(() -> service.request(request, "key-b-651", userId()));
            // 앞 요청이 커밋하기 전에는 계약 잠금에서 기다린다.
            assertThatThrownBy(() -> second.get(300, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            release.countDown();
            assertThat(second.get(15, TimeUnit.SECONDS).acquisitionCostCheckId())
                    .isNotEqualTo(first.get(15, TimeUnit.SECONDS).acquisitionCostCheckId());
        }
        assertThat(stored(contract)).isEqualTo(before + 2);
    }
}
