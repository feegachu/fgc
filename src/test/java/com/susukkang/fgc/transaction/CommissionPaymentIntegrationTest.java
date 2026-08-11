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
import com.susukkang.fgc.transaction.dto.CommissionPaymentUpdateRequest;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Test
    void persistsAndConfirmsPaymentAgainstProjectErd() {
        CommissionPaymentCreateRequest request = request(
                "IT-FUN065-" + UUID.randomUUID()
        );

        CommissionPaymentResponse created = commissionPaymentService.create(request);
        CommissionPaymentResponse confirmed = commissionPaymentService.confirm(
                created.paymentId(),
                "IT-CONFIRM-" + created.paymentId()
        );

        assertThat(created.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        assertThat(created.contractId()).isEqualTo(1L);
        assertThat(confirmed.status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(confirmed.attributions()).singleElement()
                .extracting(attribution -> attribution.contractId())
                .isEqualTo(1L);
        assertThat(confirmed.allocationPolicyVersion()).isEqualTo(4L);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT attribution_date
                  FROM fgc.transaction_attribution
                 WHERE commission_transaction_id = ?
                   AND attribution_seq = 1
                """, LocalDate.class, created.paymentId()))
                .isEqualTo(LocalDate.of(2026, 7, 31));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT attribution_month
                  FROM fgc.transaction_attribution
                 WHERE commission_transaction_id = ?
                   AND attribution_seq = 1
                """, LocalDate.class, created.paymentId()))
                .isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(jdbcTemplate.queryForObject("""
                SELECT as_of_date
                  FROM fgc.cap_check
                 WHERE candidate_transaction_id = ?
                 ORDER BY cap_check_id DESC
                 LIMIT 1
                """, LocalDate.class, created.paymentId()))
                .isEqualTo(LocalDate.of(2026, 7, 31));
    }

    // 2026-08-11 yslee - 귀속행 입력 전 DRAFT의 PostgreSQL 저장·확정 경계 검증
    // 기존 코드: 빈 귀속 목록은 DTO와 일괄 INSERT 및 INNER JOIN 조회에서 차단
    // 문제: 화면의 지급 본문 선저장 흐름을 재현할 수 없고 누락 확정이 잘못된 오류로 응답
    // 개선: DRAFT 본문은 저장하되 확정은 TRAN-002로 차단하고 DATA_QUALITY 이력을 보존
    @Test
    void persistsDraftWithoutAttributionsAndBlocksConfirmation() {
        String runId = UUID.randomUUID().toString();
        CommissionPaymentCreateRequest source = request(
                "IT-FUN065-EMPTY-" + runId,
                BigDecimal.ZERO
        );
        CommissionPaymentCreateRequest emptyDraft = new CommissionPaymentCreateRequest(
                source.sourceType(),
                source.sourceBusinessKey(),
                source.contractId(),
                source.agentId(),
                source.commissionItemId(),
                source.amount(),
                source.settlementMonth(),
                source.cashflowType(),
                source.scheduledPaymentDate(),
                source.paymentStage(),
                source.allocationPolicyVersion(),
                List.of(),
                source.note()
        );

        CommissionPaymentResponse created = commissionPaymentService.create(emptyDraft);

        assertThat(created.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        assertThat(created.attributions()).isEmpty();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.transaction_attribution
                 WHERE commission_transaction_id = ?
                """, Integer.class, created.paymentId())).isZero();

        assertThatThrownBy(() -> commissionPaymentService.confirm(
                created.paymentId(), "IT-EMPTY-" + runId
        )).isInstanceOfSatisfying(FgcBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(FgcErrorCode.TRAN_002));
        assertThat(paymentStatus(created.paymentId())).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT exception_type
                  FROM fgc.exception_case
                 WHERE source_entity_type = 'COMMISSION_TRANSACTION'
                   AND source_entity_id = ?
                """, String.class, String.valueOf(created.paymentId())))
                .isEqualTo("DATA_QUALITY");
    }

    // 2026-08-11 yslee - CAP_RULE_MISMATCH 예외 유형의 실제 DB CHECK 통합 검증
    // 기존 코드: 서비스가 사용하는 유형이 baseline CHECK 허용 목록에서 누락
    // 문제: 정책 불일치 이력 INSERT가 DataIntegrityViolationException으로 실패
    // 개선: V13 적용 후 동일 유형을 정상 저장하고 조회할 수 있는지 PostgreSQL에서 검증
    @Test
    void databaseAcceptsCapRuleMismatchExceptionType() {
        String exceptionKey = "IT-FUN065-CAP-RULE-MISMATCH-" + UUID.randomUUID();

        int inserted = jdbcTemplate.update("""
                INSERT INTO fgc.exception_case (
                    exception_key,
                    exception_type,
                    severity,
                    status,
                    source_entity_type,
                    source_entity_id,
                    title
                ) VALUES (?, 'CAP_RULE_MISMATCH', 'HIGH', 'NEW',
                          'COMMISSION_TRANSACTION', 'IT-FUN065', '한도 정책 불일치')
                """, exceptionKey);

        assertThat(inserted).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT exception_type
                  FROM fgc.exception_case
                 WHERE exception_key = ?
                """, String.class, exceptionKey)).isEqualTo("CAP_RULE_MISMATCH");
    }

    // 2026-08-11 yslee - V14의 불필요한 지급 순번 구조 제거 통합 검증
    // 기존 코드: source_sequence 컬럼과 순번 포함 UNIQUE 인덱스가 API 입력을 강제
    // 문제: 기존 source_business_key 중복 제약이 있는데 순번만 바꿔 동일 지급을 우회 저장 가능
    // 개선: 후속 Flyway가 순번 컬럼·인덱스를 제거하고 source_contract_id는 유지하는지 확인
    @Test
    void databaseRemovesPaymentSequenceButKeepsSourceContract() {
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'fgc'
                   AND table_name = 'commission_transaction'
                   AND column_name = 'source_sequence'
                """, Integer.class)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT to_regclass('fgc.uq_commission_payment_natural')",
                String.class
        )).isNull();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM information_schema.columns
                 WHERE table_schema = 'fgc'
                   AND table_name = 'commission_transaction'
                   AND column_name = 'source_contract_id'
                """, Integer.class)).isEqualTo(1);
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
                "GA_MANUAL_PAYMENT",
                "IT-FUN065-MULTI-" + runId,
                1L,
                6L,
                commissionItemId(),
                new BigDecimal("20"),
                LocalDate.of(2026, 7, 1),
                "PAYMENT",
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
        CommissionPaymentResponse confirmed = commissionPaymentService.confirm(
                created.paymentId(),
                "IT-CONFIRM-MULTI-" + runId
        );

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

    // 2026-08-11 yslee - 원천 업무키 하나로 지급 건 중복을 차단하는 DB 계약 검증
    // 기존 코드: 계약·설계사·항목·정산월에 사용자가 입력한 순번까지 붙여 중복을 판단
    // 문제: 순번만 바꾸면 같은 지급을 다시 저장할 수 있어 중복 방지 목적과 충돌
    // 개선: 서로 다른 업무키는 허용하고 기존 source_type+source_business_key UNIQUE만 동일 요청을 차단
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsDuplicateSourceBusinessKeyInDatabase() {
        String runId = UUID.randomUUID().toString();
        String sourceBusinessKey = "IT-FUN065-A-" + runId;
        CommissionPaymentResponse first = commissionPaymentService.create(request(sourceBusinessKey));
        CommissionPaymentResponse differentBusinessKey = commissionPaymentService.create(request(
                "IT-FUN065-B-" + runId
        ));

        assertThat(differentBusinessKey.paymentId()).isNotEqualTo(first.paymentId());

        assertThatThrownBy(() -> commissionPaymentService.create(
                request(sourceBusinessKey)
        )).isInstanceOf(DataIntegrityViolationException.class);
    }

    // 2026-08-11 yslee - 이슈 #65 원수사 명세 DRAFT의 수기 지급 수정 경로 차단 검증
    // 기존 코드: 상태가 DRAFT이면 source_type과 무관하게 PUT이 원수사 명세 원장을 덮어씀
    // 문제: 외부 명세 사실이 수기 지급 화면의 계약·금액·업무키로 변조되어 대사 추적성이 사라짐
    // 개선: GA_MANUAL_PAYMENT 행만 갱신하는 SQL 조건을 실제 PostgreSQL에서 검증
    @Test
    void doesNotOverwriteInsurerStatementDraftThroughManualPaymentUpdate() {
        String sourceBusinessKey = "IT-FUN065-INSURER-" + UUID.randomUUID();
        Long paymentId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage,
                    source_type,
                    source_business_key,
                    source_contract_id,
                    recipient_agent_id,
                    commission_item_id,
                    policy_version_id,
                    settlement_month,
                    due_date,
                    amount,
                    cashflow_type,
                    status
                ) VALUES (
                    'INSURER_TO_GA',
                    'INSURER_STATEMENT',
                    ?,
                    1,
                    6,
                    ?,
                    4,
                    DATE '2026-07-01',
                    DATE '2026-07-31',
                    100,
                    'PAYMENT',
                    'DRAFT'
                )
                RETURNING commission_transaction_id
                """, Long.class, sourceBusinessKey, commissionItemId());
        jdbcTemplate.update("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id,
                    attribution_seq,
                    attribution_scope,
                    contract_id,
                    agent_id,
                    attribution_date,
                    attribution_month,
                    attributed_amount,
                    inclusion_status_snapshot,
                    attribution_method,
                    allocation_basis_snapshot,
                    evidence_ref
                ) VALUES (
                    ?,
                    1,
                    'CONTRACT',
                    1,
                    6,
                    DATE '2026-07-31',
                    DATE '2026-07-01',
                    100,
                    'INCLUDED',
                    'DIRECT',
                    '{}'::jsonb,
                    'IT-INSURER-STATEMENT'
                )
                """, paymentId);
        CommissionPaymentCreateRequest manualRequest = request(
                "IT-FUN065-MANUAL-OVERWRITE-" + UUID.randomUUID(),
                new BigDecimal("999")
        );

        assertThatThrownBy(() -> commissionPaymentService.update(
                paymentId,
                updateRequest(manualRequest, new BigDecimal("999"))
        )).isInstanceOfSatisfying(FgcBusinessException.class,
                exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(FgcErrorCode.TRAN_005));

        assertThat(jdbcTemplate.queryForMap("""
                SELECT source_type, source_business_key, amount
                  FROM fgc.commission_transaction
                 WHERE commission_transaction_id = ?
                """, paymentId))
                .containsEntry("source_type", "INSURER_STATEMENT")
                .containsEntry("source_business_key", sourceBusinessKey)
                .containsEntry("amount", new BigDecimal("100.00"));
    }

    // 2026-08-11 yslee - 같은 계약의 두 DRAFT 확정을 PostgreSQL 계약 행 잠금으로 직렬화
    // 기존 코드: 지급 건별 FOR UPDATE만 사용하여 서로 다른 DRAFT가 같은 기존 산입액을 동시에 조회
    // 문제: 각각은 한도 이내지만 합계는 초과하는 두 요청이 모두 CONFIRMED가 될 수 있음
    // 개선: 동일 계약 200,000원 요청 두 건을 동시에 실행해 한 건 성공·한 건 한도 차단을 검증
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void serializesConcurrentConfirmationsForSameContract() throws Exception {
        String runId = UUID.randomUUID().toString();
        CommissionPaymentResponse first = commissionPaymentService.create(request(
                "IT-FUN065-LOCK-A-" + runId,
                new BigDecimal("200000")
        ));
        CommissionPaymentResponse second = commissionPaymentService.create(request(
                "IT-FUN065-LOCK-B-" + runId,
                new BigDecimal("200000")
        ));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<String> firstResult = executor.submit(() -> confirmOutcome(
                    first.paymentId(), "IT-LOCK-A-" + runId, ready, start
            ));
            Future<String> secondResult = executor.submit(() -> confirmOutcome(
                    second.paymentId(), "IT-LOCK-B-" + runId, ready, start
            ));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(firstResult.get(), secondResult.get()))
                    .containsExactlyInAnyOrder("CONFIRMED", "CAP_001");
        } finally {
            executor.shutdownNow();
        }

        Integer confirmedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.commission_transaction
                 WHERE commission_transaction_id IN (?, ?)
                   AND status = 'CONFIRMED'
                """, Integer.class, first.paymentId(), second.paymentId());
        assertThat(confirmedCount).isEqualTo(1);
    }

    // 2026-08-11 yslee - 성공한 확정의 멱등키와 cap_check 응답을 실제 DB에서 재현
    // 기존 코드: 동일 요청 재전송 시 DRAFT 상태 오류로 끝나 최초 cap_check 결과를 알 수 없음
    // 문제: 응답 유실 후 재시도에서 중복 검증 이력이 생기거나 성공 여부를 복구하지 못함
    // 개선: 같은 키 재요청은 추가 cap_check 없이 최초 ID 목록을 그대로 반환
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void returnsSameCapChecksForIdempotentConfirmationRetry() {
        String runId = UUID.randomUUID().toString();
        CommissionPaymentResponse created = commissionPaymentService.create(request(
                "IT-FUN065-IDEM-" + runId,
                BigDecimal.ZERO
        ));
        String idempotencyKey = "IT-IDEM-" + runId;

        CommissionPaymentResponse first = commissionPaymentService.confirm(
                created.paymentId(), idempotencyKey
        );
        CommissionPaymentResponse retried = commissionPaymentService.confirm(
                created.paymentId(), idempotencyKey
        );

        assertThat(first.capCheckIds()).isNotEmpty();
        assertThat(retried.capCheckIds()).containsExactlyElementsOf(first.capCheckIds());
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.cap_check
                 WHERE candidate_transaction_id = ?
                   AND check_kind = 'PRE_CONFIRM'
                """, Integer.class, created.paymentId()))
                .isEqualTo(first.capCheckIds().size());
    }

    // 2026-08-11 yslee - 서로 다른 지급 건 사이의 Idempotency-Key 재사용 차단 검증
    // 기존 코드: 지급 건 내부 재시도만 비교하여 같은 키가 다른 지급 건을 각각 확정할 수 있었음
    // 문제: 클라이언트 한 요청이 두 지급 건에 적용되어 중복 확정으로 해석될 수 있음
    // 개선: 전역 부분 UNIQUE로 두 번째 확정을 롤백하고 해당 지급 건과 점검 이력을 DRAFT 상태로 보존
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void rejectsIdempotencyKeyReuseAcrossDifferentPayments() {
        String runId = UUID.randomUUID().toString();
        CommissionPaymentResponse first = commissionPaymentService.create(request(
                "IT-FUN065-IDEM-CROSS-A-" + runId,
                BigDecimal.ZERO
        ));
        CommissionPaymentResponse second = commissionPaymentService.create(request(
                "IT-FUN065-IDEM-CROSS-B-" + runId,
                BigDecimal.ZERO
        ));
        String idempotencyKey = "IT-IDEM-CROSS-" + runId;

        commissionPaymentService.confirm(first.paymentId(), idempotencyKey);

        assertThatThrownBy(() -> commissionPaymentService.confirm(
                second.paymentId(), idempotencyKey
        )).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(paymentStatus(second.paymentId())).isEqualTo("DRAFT");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.cap_check
                 WHERE candidate_transaction_id = ?
                """, Integer.class, second.paymentId())).isZero();
    }

    // 2026-08-11 yslee - 실패 점검이 있는 DRAFT 수정 후 성공 확정의 멱등 응답 분리 검증
    // 기존 코드: 과거 실패 cap_check가 귀속행 FK를 잡고 수정이 막히며 재요청에는 과거 ID까지 섞임
    // 문제: 금액을 고쳐 정상 확정해도 최초 성공 응답을 동일하게 복구할 수 없음
    // 개선: 과거 상세 FK만 해제해 DRAFT를 수정하고 성공 시도의 capCheckIds만 스냅샷으로 반환
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void retriesSuccessfulConfirmationAfterRejectedDraftUpdate() {
        String runId = UUID.randomUUID().toString();
        CommissionPaymentCreateRequest rejectedRequest = request(
                "IT-FUN065-IDEM-RECOVER-" + runId,
                new BigDecimal("1300000")
        );
        CommissionPaymentResponse created = commissionPaymentService.create(rejectedRequest);
        assertCapViolation(created.paymentId());

        List<Long> rejectedCapCheckIds = jdbcTemplate.queryForList("""
                SELECT cap_check_id
                  FROM fgc.cap_check
                 WHERE candidate_transaction_id = ?
                   AND result_status = 'VIOLATION'
                 ORDER BY cap_check_id
                """, Long.class, created.paymentId());

        CommissionPaymentResponse updated = commissionPaymentService.update(
                created.paymentId(),
                updateRequest(rejectedRequest, BigDecimal.ZERO)
        );
        assertThat(updated.status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.cap_check_detail
                 WHERE cap_check_id IN (
                       SELECT cap_check_id
                         FROM fgc.cap_check
                        WHERE candidate_transaction_id = ?
                          AND result_status = 'VIOLATION'
                 )
                   AND transaction_attribution_id IS NOT NULL
                """, Integer.class, created.paymentId())).isZero();

        String idempotencyKey = "IT-IDEM-RECOVER-" + runId;
        CommissionPaymentResponse confirmed = commissionPaymentService.confirm(
                created.paymentId(), idempotencyKey
        );
        CommissionPaymentResponse retried = commissionPaymentService.confirm(
                created.paymentId(), idempotencyKey
        );

        assertThat(confirmed.capCheckIds()).doesNotContainAnyElementsOf(rejectedCapCheckIds);
        assertThat(retried.capCheckIds()).containsExactlyElementsOf(confirmed.capCheckIds());
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
        assertThatThrownBy(() -> commissionPaymentService.confirm(
                paymentId,
                "IT-CONFIRM-VIOLATION-" + paymentId
        ))
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
        // 2026-08-11 yslee - 확정 원장의 불변성 트리거를 보존하면서 통합 테스트 전용 데이터 정리
        // 기존 코드: CONFIRMED 지급 건의 귀속 행을 일반 DELETE로 먼저 제거
        // 문제: 운영 불변성 트리거가 정상 차단하여 후속 통합 테스트가 이전 데이터 때문에 실패
        // 개선: 별도 테스트 정리 트랜잭션에서만 사용자 트리거를 비활성화하고 고유 접두사의 행만 삭제
        TransactionTemplate cleanupTransaction = new TransactionTemplate(transactionManager);
        cleanupTransaction.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        cleanupTransaction.executeWithoutResult(status -> cleanupCommittedCapTestData());
    }

    private void cleanupCommittedCapTestData() {
        jdbcTemplate.execute("SET LOCAL session_replication_role = replica");
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
                                  OR source_business_key LIKE 'IT-FUN065-LOCK-%'
                                  OR source_business_key LIKE 'IT-FUN065-IDEM-%'
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
                           OR source_business_key LIKE 'IT-FUN065-LOCK-%'
                           OR source_business_key LIKE 'IT-FUN065-IDEM-%'
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
                           OR source_business_key LIKE 'IT-FUN065-LOCK-%'
                           OR source_business_key LIKE 'IT-FUN065-IDEM-%'
                   )
                """);
        jdbcTemplate.update("""
                DELETE FROM fgc.transaction_attribution
                 WHERE commission_transaction_id IN (
                       SELECT commission_transaction_id
                         FROM fgc.commission_transaction
                        WHERE source_business_key LIKE 'IT-FUN065-CAP-%'
                           OR source_business_key LIKE 'IT-FUN065-A-%'
                           OR source_business_key LIKE 'IT-FUN065-LOCK-%'
                           OR source_business_key LIKE 'IT-FUN065-IDEM-%'
                 )
                """);
        jdbcTemplate.update("""
                DELETE FROM fgc.commission_transaction
                 WHERE source_business_key LIKE 'IT-FUN065-CAP-%'
                    OR source_business_key LIKE 'IT-FUN065-A-%'
                    OR source_business_key LIKE 'IT-FUN065-LOCK-%'
                    OR source_business_key LIKE 'IT-FUN065-IDEM-%'
                """);
    }

    private CommissionPaymentCreateRequest request(
            String sourceBusinessKey
    ) {
        return request(sourceBusinessKey, BigDecimal.ZERO);
    }

    private CommissionPaymentCreateRequest request(
            String sourceBusinessKey,
            BigDecimal amount
    ) {
        return new CommissionPaymentCreateRequest(
                "GA_MANUAL_PAYMENT",
                sourceBusinessKey,
                1L,
                6L,
                commissionItemId(),
                amount,
                LocalDate.of(2026, 7, 1),
                "PAYMENT",
                LocalDate.of(2026, 7, 31),
                PaymentStage.GA_TO_FC,
                4L,
                List.of(new CommissionPaymentAttributionRequest(
                        1L,
                        LocalDate.of(2026, 7, 31),
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
                LocalDate.of(2026, 7, 31),
                amount,
                InclusionDecisionStatus.INCLUDED,
                ExclusionType.NONE,
                "통합 테스트 다중 계약 직접 귀속",
                "DIRECT",
                "IT-EVIDENCE-" + contractId,
                AttributionMethod.DIRECT
        );
    }

    private CommissionPaymentUpdateRequest updateRequest(
            CommissionPaymentCreateRequest source,
            BigDecimal amount
    ) {
        return new CommissionPaymentUpdateRequest(
                source.sourceType(),
                source.sourceBusinessKey(),
                source.contractId(),
                source.agentId(),
                source.commissionItemId(),
                amount,
                source.settlementMonth(),
                source.cashflowType(),
                source.scheduledPaymentDate(),
                source.paymentStage(),
                source.allocationPolicyVersion(),
                source.attributions().stream()
                        .map(attribution -> new CommissionPaymentAttributionRequest(
                                attribution.contractId(),
                                attribution.attributionDate(),
                                amount,
                                attribution.inclusionDecisionStatus(),
                                attribution.exclusionType(),
                                attribution.inclusionDecisionReason(),
                                attribution.allocationBasis(),
                                attribution.evidenceRef(),
                                attribution.attributionMethod()
                        ))
                        .toList(),
                source.note()
        );
    }

    private String confirmOutcome(
            Long paymentId,
            String idempotencyKey,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        start.await();
        try {
            return commissionPaymentService.confirm(paymentId, idempotencyKey)
                    .status()
                    .name();
        } catch (FgcBusinessException exception) {
            return exception.getErrorCode().name();
        }
    }

    private Long commissionItemId() {
        return jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item WHERE item_code = 'BASE_COMMISSION'",
                Long.class
        );
    }
}
