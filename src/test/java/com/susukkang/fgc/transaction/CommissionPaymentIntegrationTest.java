package com.susukkang.fgc.transaction;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentAttributionRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.AfterEach;
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
import java.util.List;
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
        assertThat(confirmed.attributions()).singleElement()
                .extracting(attribution -> attribution.contractId())
                .isEqualTo(1L);
        assertThat(confirmed.allocationPolicyVersion()).isEqualTo(4L);
        assertThat(confirmed.paymentSequence()).isEqualTo(1);
    }

    // 2026-08-10 yslee - 하나의 지급 건에 여러 계약 귀속행을 저장하고 전부 검증
    // 기존 코드: 첫 번째 귀속행만 저장·조회·확정하여 다중 계약 배부를 재현할 수 없음
    // 문제: 두 번째 이후 계약의 지급액이 FUN-033 한도 검증과 cap_check 이력에서 누락
    // 개선: 두 계약 귀속 합계와 응답 개수 및 계약별 cap_check 생성 건수를 PostgreSQL에서 검증
    @Test
    void persistsAndConfirmsMultipleContractAttributions() {
        String runId = UUID.randomUUID().toString();
        Long secondContractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class,
                "FGC-FGL01-202607-0002"
        );
        CommissionPaymentCreateRequest request = new CommissionPaymentCreateRequest(
                "IT-FUN065-MULTI-" + runId,
                3_000_000 + Math.floorMod(runId.hashCode(), 1_000_000),
                1L,
                6L,
                "BASE_COMMISSION",
                new BigDecimal("20"),
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 31),
                PaymentStage.GA_TO_FC,
                4L,
                List.of(
                        attribution(1L, new BigDecimal("10")),
                        attribution(secondContractId, new BigDecimal("10"))
                ),
                "다중 계약 귀속 통합 테스트"
        );

        CommissionPaymentResponse created = commissionPaymentService.create(request);
        CommissionPaymentResponse confirmed = commissionPaymentService.confirm(created.paymentId());

        assertThat(confirmed.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(confirmed.attributions())
                .extracting(attribution -> attribution.contractId())
                .containsExactly(1L, secondContractId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.cap_check WHERE candidate_transaction_id = ?",
                Integer.class,
                created.paymentId()
        )).isEqualTo(2);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsDuplicateManualPaymentNaturalKeyInDatabase() {
        String runId = UUID.randomUUID().toString();
        int paymentSequence = 2_000_000 + Math.floorMod(runId.hashCode(), 1_000_000);
        commissionPaymentService.create(request("IT-FUN065-A-" + runId, paymentSequence));

        assertThatThrownBy(() -> commissionPaymentService.create(
                request("IT-FUN065-B-" + runId, paymentSequence)
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
        int paymentSequence = 1000 + Math.floorMod(runId.hashCode(), 1_000_000);
        CommissionPaymentResponse created = commissionPaymentService.create(request(
                "IT-FUN065-CAP-" + runId,
                paymentSequence,
                new BigDecimal("1300000")
        ));

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
        return jdbcTemplate.queryForObject("""
                SELECT exception_key
                  FROM fgc.exception_case
                 WHERE source_entity_id = ?
                   AND exception_type = 'CAP_VIOLATION'
                """, String.class, String.valueOf(paymentId));
    }

    // 2026-08-10 yslee - 중단된 독립 커밋 통합 테스트의 잔존 데이터를 후속 실행 전에 정리
    // 기존 코드: 테스트 본문의 finally에서 현재 paymentId 한 건만 삭제
    // 문제: 생성 직후 예외나 강제 종료가 발생하면 cleanup에 진입하지 못해 다음 실행의 자연키가 충돌
    // 개선: @AfterEach에서 고유 접두사로 과거 잔존 건까지 자식 테이블 순서대로 일괄 삭제
    @AfterEach
    void cleanupCommittedCapTests() {
        jdbcTemplate.update("""
                DELETE FROM fgc.cap_check_detail
                 WHERE cap_check_id IN (
                       SELECT cap_check_id
                         FROM fgc.cap_check
                        WHERE candidate_transaction_id IN (
                              SELECT commission_transaction_id
                                FROM fgc.commission_transaction
                               WHERE source_business_key LIKE 'IT-FUN065-CAP-%'
                                  OR source_business_key LIKE 'IT-FUN065-A-%'
                        )
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM fgc.cap_check
                 WHERE candidate_transaction_id IN (
                       SELECT commission_transaction_id
                         FROM fgc.commission_transaction
                        WHERE source_business_key LIKE 'IT-FUN065-CAP-%'
                           OR source_business_key LIKE 'IT-FUN065-A-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM fgc.exception_case
                 WHERE source_entity_type = 'COMMISSION_TRANSACTION'
                   AND source_entity_id IN (
                       SELECT CAST(commission_transaction_id AS varchar)
                         FROM fgc.commission_transaction
                        WHERE source_business_key LIKE 'IT-FUN065-CAP-%'
                           OR source_business_key LIKE 'IT-FUN065-A-%'
                   )
                """);
        jdbcTemplate.update("""
                DELETE FROM fgc.transaction_attribution
                 WHERE commission_transaction_id IN (
                       SELECT commission_transaction_id
                         FROM fgc.commission_transaction
                        WHERE source_business_key LIKE 'IT-FUN065-CAP-%'
                           OR source_business_key LIKE 'IT-FUN065-A-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM fgc.commission_transaction
                 WHERE source_business_key LIKE 'IT-FUN065-CAP-%'
                    OR source_business_key LIKE 'IT-FUN065-A-%'
                """);
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
                4L,
                List.of(new CommissionPaymentAttributionRequest(
                        1L,
                        amount,
                        InclusionDecisionStatus.INCLUDED,
                        ExclusionType.NONE,
                        "통합 테스트 직접 귀속",
                        "DIRECT",
                        "IT-EVIDENCE",
                        AttributionMethod.DIRECT
                )),
                "rollback integration test"
        );
    }

    private CommissionPaymentAttributionRequest attribution(Long contractId, BigDecimal amount) {
        return new CommissionPaymentAttributionRequest(
                contractId,
                amount,
                InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE,
                "통합 테스트 다중 계약 직접 귀속",
                "DIRECT",
                "IT-EVIDENCE-" + contractId,
                AttributionMethod.DIRECT
        );
    }
}
