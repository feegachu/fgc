package com.susukkang.fgc.transaction;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 설명 : 수수료 지급 건 PostgreSQL 통합 테스트
 *
 * @author yslee
 * @since 2026-08-07
 * @version 1.2
 */
@SpringBootTest
@Transactional
class CommissionPaymentIntegrationTest {

    @Autowired
    private CommissionPaymentService commissionPaymentService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void persistsAndConfirmsPaymentAgainstProjectErd() {
        CommissionPaymentCreateRequest request = request(
                "IT-FUN065-" + UUID.randomUUID(),
                1
        );

        CommissionPaymentResponse created = commissionPaymentService.create(request);
        CommissionPaymentResponse confirmed = commissionPaymentService.confirm(
                created.paymentId()
        );

        assertThat(created.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        assertThat(created.contractId()).isEqualTo(1L);
        assertThat(confirmed.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(confirmed.attributedContractId()).isEqualTo(1L);
        assertThat(confirmed.allocationPolicyVersion()).isEqualTo(4L);
        assertThat(confirmed.paymentSequence()).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateManualPaymentNaturalKeyInDatabase() {
        String runId = UUID.randomUUID().toString();
        commissionPaymentService.create(request("IT-FUN065-A-" + runId, 99));

        assertThatThrownBy(() -> commissionPaymentService.create(
                request("IT-FUN065-B-" + runId, 99)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    // 2026-08-10 yslee - PostgreSQL에서 확정 실패의 잠금·트랜잭션·예외 상태를 통합 검증
    // 기존 코드: REQUIRES_NEW 구조를 Mockito 단위 테스트로만 확인
    // 문제: FOR UPDATE 부모 잠금과 cap_check FK 검사의 상호 대기를 실제 DB에서 발견할 수 없음
    // 개선: 독립 커밋된 DRAFT를 확정해 제한 시간 내 차단·이력 커밋·종결 상태 보존을 검증
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void capViolationKeepsDraftAndPersistsFailureWithoutReopeningResolvedException() {
        String runId = UUID.randomUUID().toString();
        CommissionPaymentResponse created = commissionPaymentService.create(request(
                "IT-FUN065-CAP-" + runId,
                1000,
                new BigDecimal("1300000")
        ));

        try {
            assertThat(created.status()).isEqualTo(CommissionPaymentStatus.DRAFT);

            assertCapViolation(created.paymentId());

            assertThat(paymentStatus(created.paymentId())).isEqualTo("DRAFT");
            assertThat(countCapViolations(created.paymentId())).isEqualTo(1);
            assertThat(exceptionStatus(created.paymentId())).isEqualTo("NEW");

            jdbcTemplate.update("""
                    UPDATE fgc.exception_case
                       SET status = 'RESOLVED',
                           resolved_at = clock_timestamp()
                     WHERE exception_key = ?
                    """, exceptionKey(created.paymentId()));

            assertCapViolation(created.paymentId());

            assertThat(paymentStatus(created.paymentId())).isEqualTo("DRAFT");
            assertThat(exceptionStatus(created.paymentId())).isEqualTo("RESOLVED");
        } finally {
            cleanupPayment(created.paymentId());
        }
    }

    private void assertCapViolation(Long paymentId) {
        assertThatThrownBy(() -> commissionPaymentService.confirm(paymentId))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(FgcErrorCode.CAP_001));
    }

    private String paymentStatus(Long paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.commission_transaction WHERE commission_transaction_id = ?",
                String.class,
                paymentId
        );
    }

    private int countCapViolations(Long paymentId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.cap_check
                 WHERE candidate_transaction_id = ?
                   AND result_status = 'VIOLATION'
                """, Integer.class, paymentId);
    }

    private String exceptionStatus(Long paymentId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM fgc.exception_case WHERE exception_key = ?",
                String.class,
                exceptionKey(paymentId)
        );
    }

    private String exceptionKey(Long paymentId) {
        return "PRE_CONFIRM:" + paymentId + ":CAP_VIOLATION";
    }

    private void cleanupPayment(Long paymentId) {
        jdbcTemplate.update("""
                DELETE FROM fgc.cap_check_detail
                 WHERE cap_check_id IN (
                       SELECT cap_check_id
                         FROM fgc.cap_check
                        WHERE candidate_transaction_id = ?
                 )
                """, paymentId);
        jdbcTemplate.update(
                "DELETE FROM fgc.cap_check WHERE candidate_transaction_id = ?",
                paymentId
        );
        jdbcTemplate.update(
                "DELETE FROM fgc.exception_case WHERE source_entity_id = ?",
                String.valueOf(paymentId)
        );
        jdbcTemplate.update(
                "DELETE FROM fgc.transaction_attribution WHERE commission_transaction_id = ?",
                paymentId
        );
        jdbcTemplate.update(
                "DELETE FROM fgc.commission_transaction WHERE commission_transaction_id = ?",
                paymentId
        );
    }

    private CommissionPaymentCreateRequest request(
            String sourceBusinessKey,
            int paymentSequence
    ) {
        return request(sourceBusinessKey, paymentSequence, BigDecimal.ZERO);
    }

    private CommissionPaymentCreateRequest request(
            String sourceBusinessKey,
            int paymentSequence,
            BigDecimal amount
    ) {
        return new CommissionPaymentCreateRequest(
                sourceBusinessKey,
                paymentSequence,
                1L,
                6L,
                "BASE_COMMISSION",
                amount,
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 31),
                PaymentStage.GA_TO_FC,
                1L,
                InclusionDecisionStatus.INCLUDED,
                "통합 테스트 직접 귀속",
                4L,
                "DIRECT",
                "IT-EVIDENCE",
                AttributionMethod.DIRECT,
                "rollback integration test"
        );
    }
}
