package com.susukkang.fgc.transaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.transaction.dto.CommissionPaymentAttributionRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentUpdateRequest;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.concurrent.DelegatingSecurityContextExecutorService;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : 오래된 지급 초안 수정 요청 통합 재현(#753).
 * 클래스에 @Transactional을 두지 않아 요청마다 실제 PostgreSQL 트랜잭션으로 커밋한다.
 *
 * @author yslee
 * @since 2026-11-04
 * @version 1.0
 */
@SpringBootTest
@AutoConfigureMockMvc
class CommissionPaymentStaleUpdateIntegrationTest {
    private static final String KEY_PREFIX = "IT-FUN065-LOCK-753-";
    private static final String TEST_PAYMENTS = "SELECT commission_transaction_id FROM fgc.commission_transaction"
            + " WHERE source_business_key LIKE '" + KEY_PREFIX + "%'";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private FgcUserDetailsService users;
    @Autowired private CommissionPaymentService payments;

    // MockMvc 요청은 끝나면서 이 스레드의 SecurityContext를 지운다. 서비스 직접 호출은 첫 요청 전이나 다른 스레드에서만 쓴다.
    @BeforeEach
    void authenticate() {
        FgcUserDetails principal = settlement();
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void cleanup() {
        SecurityContextHolder.clearContext();
        TransactionTemplate cleanup = new TransactionTemplate(transactionManager);
        cleanup.setPropagationBehavior(Propagation.REQUIRES_NEW.value());
        cleanup.executeWithoutResult(status -> {
            // 임시 PostgreSQL의 테스트 전용 정리. 운영 불변 트리거는 변경하지 않고 이 트랜잭션에서만 우회한다.
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("DELETE FROM fgc.cap_check_detail WHERE cap_check_id IN (SELECT cap_check_id FROM fgc.cap_check"
                    + " WHERE candidate_transaction_id IN (" + TEST_PAYMENTS + "))");
            jdbc.update("DELETE FROM fgc.cap_check WHERE candidate_transaction_id IN (" + TEST_PAYMENTS + ")");
            jdbc.update("DELETE FROM fgc.exception_case WHERE source_entity_type = 'COMMISSION_TRANSACTION'"
                    + " AND source_entity_id IN (SELECT CAST(commission_transaction_id AS varchar) FROM fgc.commission_transaction"
                    + " WHERE source_business_key LIKE '" + KEY_PREFIX + "%')");
            jdbc.update("DELETE FROM fgc.transaction_attribution WHERE commission_transaction_id IN (" + TEST_PAYMENTS + ")");
            jdbc.update("DELETE FROM fgc.commission_transaction WHERE source_business_key LIKE '" + KEY_PREFIX + "%'");
        });
    }

    // 2026-10-28 QA 반례(A 2,000원 저장 뒤 B가 옛 1,000원으로 메모만 수정)를 별도 요청으로 재현한다.
    @Test
    void staleFullReplacementCannotRevertAnotherEditorsAmount() throws Exception {
        CommissionPaymentCreateRequest source = createRequest("1000");
        Long id = payments.create(source).paymentId();
        String editorA = detail(id).path("updatedAt").asText();
        String editorB = detail(id).path("updatedAt").asText();
        assertThat(editorB).isEqualTo(editorA);

        save(id, source, "2000", "original", editorA).andExpect(status().isOk());
        Long attributionAfterA = attributionId(id);
        save(id, source, "1000", "B 메모", editorB)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-TRAN-010"));

        assertStored(id, "2000", "original");
        assertThat(attributionId(id)).isEqualTo(attributionAfterA);
        assertThat(updateAudits(id)).isEqualTo(1);

        // 최신 상세를 다시 읽은 B의 의도적 재편집은 저장되고 감사 before는 A의 커밋을 가리킨다.
        JsonNode latest = detail(id);
        assertThat(latest.path("amount").decimalValue()).isEqualByComparingTo("2000");
        save(id, source, "2000", "B 메모", latest.path("updatedAt").asText()).andExpect(status().isOk());
        assertStored(id, "2000", "B 메모");
        assertThat(updateAudits(id)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT (before_value->'payment'->>'amount')::numeric FROM fgc.audit_log
                 WHERE action_code = 'PAYMENT_UPDATED' AND entity_id = ? ORDER BY audit_log_id DESC LIMIT 1
                """, BigDecimal.class, id.toString())).isEqualByComparingTo("2000");
    }

    @Test
    void rejectedStaleSaveLeavesSourceAttributionValidationLinksAndAuditUntouched() throws Exception {
        CommissionPaymentCreateRequest source = createRequest("1000");
        Long id = payments.create(source).paymentId();
        String stale = detail(id).path("updatedAt").asText();
        save(id, source, "1300000", "original", stale).andExpect(status().isOk());
        // 한도 초과 확정 거절이 현재 귀속행에 연결된 PRE_CONFIRM 검출 근거와 예외를 남긴다.
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/transactions/{id}/confirm", id)
                        .with(settlementUser()).with(csrf()).header("Idempotency-Key", KEY_PREFIX + "CONFIRM-" + id))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FGC-CAP-001"));
        List<Long> links = validationLinks(id);
        assertThat(links).isNotEmpty();
        Long attribution = attributionId(id);
        String tokenBefore = detail(id).path("updatedAt").asText();
        long exceptions = count("SELECT count(*) FROM fgc.exception_case WHERE source_entity_type = 'COMMISSION_TRANSACTION'"
                + " AND source_entity_id = ?", id.toString());
        long audits = count("SELECT count(*) FROM fgc.audit_log WHERE entity_type = 'COMMISSION_PAYMENT' AND entity_id = ?", id.toString());

        save(id, source, "1000", "B 메모", stale).andExpect(status().isConflict());

        assertStored(id, "1300000", "original");
        assertThat(detail(id).path("updatedAt").asText()).isEqualTo(tokenBefore);
        assertThat(attributionId(id)).isEqualTo(attribution);
        assertThat(validationLinks(id)).isEqualTo(links);
        assertThat(count("SELECT count(*) FROM fgc.exception_case WHERE source_entity_type = 'COMMISSION_TRANSACTION'"
                + " AND source_entity_id = ?", id.toString())).isEqualTo(exceptions);
        assertThat(count("SELECT count(*) FROM fgc.audit_log WHERE entity_type = 'COMMISSION_PAYMENT' AND entity_id = ?", id.toString()))
                .isEqualTo(audits);

        // 같은 내용을 최신 토큰으로 보내면 정상 경로대로 검출 근거 연결을 끊는다. 위 불변이 토큰 거절 때문임을 대조한다.
        save(id, source, "1000", "B 메모", tokenBefore).andExpect(status().isOk());
        assertThat(validationLinks(id)).isEmpty();
    }

    @Test
    void tokenIsComparedAtMicrosecondPrecisionAcrossOffsets() throws Exception {
        CommissionPaymentCreateRequest source = createRequest("1000");
        Long id = payments.create(source).paymentId();
        // 밀리초 아래 자리가 있는 시각을 고정해 JSON 왕복과 비교 정밀도를 결정적으로 확인한다.
        Instant stored = Instant.parse("2026-11-04T01:02:03.123456Z");
        TransactionTemplate fixture = new TransactionTemplate(transactionManager);
        fixture.executeWithoutResult(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica");
            jdbc.update("UPDATE fgc.commission_transaction SET updated_at = ? WHERE commission_transaction_id = ?",
                    stored.atOffset(ZoneOffset.UTC), id);
        });
        String token = detail(id).path("updatedAt").asText();
        OffsetDateTime parsed = OffsetDateTime.parse(token);
        assertThat(parsed.toInstant()).isEqualTo(stored);

        for (OffsetDateTime other : List.of(parsed.minusNanos(1000), parsed.plusNanos(1000), parsed.truncatedTo(ChronoUnit.MILLIS))) {
            save(id, source, "2000", "original", other.toString())
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("FGC-TRAN-010"));
        }
        assertStored(id, "1000", "original");

        save(id, source, "2000", "original", parsed.withOffsetSameInstant(ZoneOffset.ofHours(9)).toString())
                .andExpect(status().isOk());
        assertStored(id, "2000", "original");
    }

    // 같은 값 재저장도 UPDATE와 updated_at 트리거를 지나므로(CommissionTransactionRepository) 버전이 올라간다.
    @Test
    void identicalResaveAdvancesTheTokenSoAnOlderSnapshotIsRejected() throws Exception {
        CommissionPaymentCreateRequest source = createRequest("1000");
        Long id = payments.create(source).paymentId();
        String first = detail(id).path("updatedAt").asText();

        save(id, source, "1000", "original", first).andExpect(status().isOk());
        String second = detail(id).path("updatedAt").asText();
        assertThat(OffsetDateTime.parse(second)).isAfter(OffsetDateTime.parse(first));

        save(id, source, "1000", "B 메모", first).andExpect(status().isConflict());
        assertStored(id, "1000", "original");
        assertThat(updateAudits(id)).isEqualTo(1);

        save(id, source, "1000", "original", second).andExpect(status().isOk());
        assertThat(updateAudits(id)).isEqualTo(2);
    }

    @Test
    @Timeout(40)
    void concurrentSavesOfSameDraftLetOnlyTheFirstCommit() throws Exception {
        CommissionPaymentCreateRequest source = createRequest("1000");
        Long id = payments.create(source).paymentId();
        OffsetDateTime token = payments.get(id).updatedAt();

        // 두 번째 저장은 행 잠금을 기다린 뒤 비교하므로, 조회 시점이 같아도 첫 커밋을 보고 거절된다.
        FgcErrorCode second = secondOutcomeAfterFirstCommits(
                () -> payments.update(id, updateRequest(source, "2000", "A", token)),
                () -> payments.update(id, updateRequest(source, "3000", "B", token)));

        assertThat(second).isEqualTo(FgcErrorCode.TRAN_010);
        assertStored(id, "2000", "A");
        assertThat(updateAudits(id)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(40)
    void updateAndConfirmationSerializeOnThePaymentRow(boolean updateFirst) throws Exception {
        CommissionPaymentCreateRequest source = createRequest("1000");
        Long id = payments.create(source).paymentId();
        OffsetDateTime token = payments.get(id).updatedAt();
        Runnable update = () -> payments.update(id, updateRequest(source, "2000", "edited", token));
        Runnable confirm = () -> payments.confirm(id, KEY_PREFIX + "RACE-" + id);

        FgcErrorCode second = updateFirst
                ? secondOutcomeAfterFirstCommits(update, confirm)
                : secondOutcomeAfterFirstCommits(confirm, update);

        assertThat(payments.get(id).status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        if (updateFirst) {
            assertThat(second).isNull();
            assertStored(id, "2000", "edited");
        } else {
            assertThat(second).isEqualTo(FgcErrorCode.TRAN_005);
            assertStored(id, "1000", "original");
            assertThat(updateAudits(id)).isZero();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    @Timeout(40)
    void updateAndDeletionSerializeOnThePaymentRow(boolean updateFirst) throws Exception {
        CommissionPaymentCreateRequest source = createRequest("1000");
        Long id = payments.create(source).paymentId();
        OffsetDateTime token = payments.get(id).updatedAt();
        Runnable update = () -> payments.update(id, updateRequest(source, "2000", "edited", token));
        Runnable delete = () -> payments.deleteDraft(id);

        FgcErrorCode second = updateFirst
                ? secondOutcomeAfterFirstCommits(update, delete)
                : secondOutcomeAfterFirstCommits(delete, update);

        assertThat(count("SELECT count(*) FROM fgc.commission_transaction WHERE commission_transaction_id = ?", id)).isZero();
        assertThat(second).isEqualTo(updateFirst ? null : FgcErrorCode.COMMON_002);
        assertThat(updateAudits(id)).isEqualTo(updateFirst ? 1 : 0);
        assertThat(jdbc.queryForObject("""
                SELECT (before_value->'payment'->>'amount')::numeric FROM fgc.audit_log
                 WHERE action_code = 'PAYMENT_DELETED' AND entity_id = ?
                """, BigDecimal.class, id.toString())).isEqualByComparingTo(updateFirst ? "2000" : "1000");
    }

    @Test
    void rejectedSaveKeepsDraftGuardAndIdempotentConfirmation() {
        CommissionPaymentCreateRequest source = createRequest("1000");
        Long id = payments.create(source).paymentId();
        OffsetDateTime stale = payments.get(id).updatedAt();
        payments.update(id, updateRequest(source, "2000", "A", stale));
        assertRejected(() -> payments.update(id, updateRequest(source, "1000", "B", stale)), FgcErrorCode.TRAN_010);

        String key = KEY_PREFIX + "IDEMPOTENT-" + id;
        CommissionPaymentResponse confirmed = payments.confirm(id, key);
        assertThat(confirmed.capCheckIds()).isNotEmpty();
        assertThat(payments.confirm(id, key).capCheckIds()).containsExactlyElementsOf(confirmed.capCheckIds());

        // 확정 뒤에는 토큰이 맞아도 틀려도 DRAFT 보호가 먼저 거절한다.
        OffsetDateTime latest = payments.get(id).updatedAt();
        assertRejected(() -> payments.update(id, updateRequest(source, "3000", "B", latest)), FgcErrorCode.TRAN_005);
        assertRejected(() -> payments.update(id, updateRequest(source, "3000", "B", stale)), FgcErrorCode.TRAN_005);
        assertStored(id, "2000", "A");
    }

    @Test
    void readOnlyRoleCannotSaveEvenWithTheLatestToken() throws Exception {
        CommissionPaymentCreateRequest source = createRequest("1000");
        Long id = payments.create(source).paymentId();
        String token = detail(id).path("updatedAt").asText();

        save(user("comp01").roles("COMPLIANCE"), id, source, "2000", "original", token).andExpect(status().isForbidden());

        assertStored(id, "1000", "original");
        assertThat(updateAudits(id)).isZero();
    }

    // 지급 수정 경로에는 월잠금 판정이 없다(기존 동작). 토큰 비교가 그 달의 확정 실행을 건드리지 않음을 확인한다.
    // 확정 실행이 커밋되면 같은 달을 쓰는 다른 시험에 영향을 주므로 이 시험만 트랜잭션으로 되돌린다.
    @Test
    @Transactional
    void savesInFinalizedMonthLeaveTheLockedRunUntouched() {
        CommissionPaymentCreateRequest source = createRequest("1000");
        Long id = payments.create(source).paymentId();
        OffsetDateTime stale = payments.get(id).updatedAt();
        Long run = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run(validation_month, run_no, run_type)
                SELECT DATE '2026-07-01', COALESCE(MAX(run_no), 0) + 1, 'PRE_CONFIRM' FROM fgc.validation_run
                 WHERE validation_month = DATE '2026-07-01'
                RETURNING validation_run_id
                """, Long.class);
        jdbc.update("UPDATE fgc.validation_run SET status = 'RUNNING', current_step = 1, started_at = now() WHERE validation_run_id = ?", run);
        jdbc.update("UPDATE fgc.validation_run SET status = 'COMPLETED', current_step = 8, completed_at = now() WHERE validation_run_id = ?", run);
        jdbc.update("UPDATE fgc.validation_run SET status = 'FINALIZED', current_step = 10, finalized_at = now() WHERE validation_run_id = ?", run);
        String lockedRun = jdbc.queryForObject("SELECT to_jsonb(v)::text FROM fgc.validation_run v WHERE validation_run_id = ?", String.class, run);

        payments.update(id, updateRequest(source, "2000", "A", stale));
        assertRejected(() -> payments.update(id, updateRequest(source, "1000", "B", stale)), FgcErrorCode.TRAN_010);

        assertStored(id, "2000", "A");
        assertThat(jdbc.queryForObject("SELECT to_jsonb(v)::text FROM fgc.validation_run v WHERE validation_run_id = ?", String.class, run))
                .isEqualTo(lockedRun);
    }

    /** 첫 작업의 커밋 전까지 두 번째 작업이 실제 DB 행 잠금을 기다리는 것을 확인하고, 두 번째의 업무 오류를 돌려준다. */
    private FgcErrorCode secondOutcomeAfterFirstCommits(Runnable first, Runnable second) throws Exception {
        ExecutorService executor = new DelegatingSecurityContextExecutorService(Executors.newFixedThreadPool(2));
        CountDownLatch firstDone = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        AtomicInteger secondBackend = new AtomicInteger();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try {
            Future<?> firstCommit = executor.submit(() -> transaction.executeWithoutResult(status -> {
                first.run();
                firstDone.countDown();
                try {
                    if (!release.await(20, TimeUnit.SECONDS)) throw new IllegalStateException("release timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(firstDone.await(10, TimeUnit.SECONDS)).isTrue();
            Future<FgcErrorCode> secondOutcome = executor.submit(() -> transaction.execute(status -> {
                secondBackend.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                secondStarted.countDown();
                try {
                    second.run();
                    return null;
                } catch (FgcBusinessException exception) {
                    status.setRollbackOnly();
                    return exception.getErrorCode();
                }
            }));
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            awaitDatabaseLock(secondBackend.get());
            release.countDown();
            firstCommit.get(10, TimeUnit.SECONDS);
            return secondOutcome.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    private void awaitDatabaseLock(int backendPid) {
        await().atMost(Duration.ofSeconds(10)).until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM pg_stat_activity WHERE pid = ? AND wait_event_type = 'Lock')",
                Boolean.class, backendPid)));
    }

    private void assertRejected(Runnable save, FgcErrorCode code) {
        assertThatThrownBy(save::run).isInstanceOfSatisfying(FgcBusinessException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(code));
    }

    private JsonNode detail(Long id) throws Exception {
        return json.readTree(mvc.perform(MockMvcRequestBuilders.get("/api/v1/transactions/{id}", id).with(settlementUser()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString()).path("data");
    }

    private ResultActions save(Long id, CommissionPaymentCreateRequest source, String amount, String note, String token)
            throws Exception {
        return save(settlementUser(), id, source, amount, note, token);
    }

    // 프런트와 같이 응답의 updatedAt 문자열을 그대로 보낸다.
    private ResultActions save(RequestPostProcessor actor, Long id, CommissionPaymentCreateRequest source,
            String amount, String note, String token) throws Exception {
        ObjectNode body = json.valueToTree(updateRequest(source, amount, note, null));
        body.put("expectedUpdatedAt", token);
        return mvc.perform(MockMvcRequestBuilders.put("/api/v1/transactions/{id}", id)
                .with(actor).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body.toString()));
    }

    private void assertStored(Long id, String amount, String note) {
        assertThat(jdbc.queryForObject("SELECT amount FROM fgc.commission_transaction WHERE commission_transaction_id = ?",
                BigDecimal.class, id)).isEqualByComparingTo(amount);
        assertThat(jdbc.queryForObject("SELECT note FROM fgc.commission_transaction WHERE commission_transaction_id = ?",
                String.class, id)).isEqualTo(note);
        assertThat(jdbc.queryForObject("SELECT attributed_amount FROM fgc.transaction_attribution WHERE commission_transaction_id = ?",
                BigDecimal.class, id)).isEqualByComparingTo(amount);
    }

    private Long attributionId(Long id) {
        return jdbc.queryForObject("SELECT transaction_attribution_id FROM fgc.transaction_attribution WHERE commission_transaction_id = ?",
                Long.class, id);
    }

    private List<Long> validationLinks(Long id) {
        return jdbc.queryForList("""
                SELECT d.transaction_attribution_id FROM fgc.cap_check_detail d
                  JOIN fgc.cap_check c ON c.cap_check_id = d.cap_check_id
                 WHERE c.candidate_transaction_id = ? AND c.check_kind = 'PRE_CONFIRM'
                   AND d.transaction_attribution_id IS NOT NULL
                 ORDER BY d.cap_check_detail_id
                """, Long.class, id);
    }

    private long updateAudits(Long id) {
        return count("SELECT count(*) FROM fgc.audit_log WHERE action_code = 'PAYMENT_UPDATED' AND entity_id = ?", id.toString());
    }

    private long count(String sql, Object parameter) {
        return jdbc.queryForObject(sql, Long.class, parameter);
    }

    private FgcUserDetails settlement() {
        return (FgcUserDetails) users.loadUserByUsername("settle01");
    }

    private RequestPostProcessor settlementUser() {
        return user(settlement());
    }

    private CommissionPaymentCreateRequest createRequest(String amount) {
        return new CommissionPaymentCreateRequest(
                "GA_MANUAL_PAYMENT", KEY_PREFIX + UUID.randomUUID(), 1L, 6L,
                jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code = 'BASE_COMMISSION'", Long.class),
                new BigDecimal(amount), LocalDate.of(2026, 7, 1), "PAYMENT", LocalDate.of(2026, 7, 31), PaymentStage.GA_TO_FC,
                jdbc.queryForObject("SELECT policy_version_id FROM fgc.policy_version WHERE policy_code = 'REG-CAP-GA-2026-V1' AND status = 'ACTIVE'", Long.class),
                attributions(amount), "IT-753-EVIDENCE", "original");
    }

    private CommissionPaymentUpdateRequest updateRequest(CommissionPaymentCreateRequest source, String amount, String note,
            OffsetDateTime token) {
        return new CommissionPaymentUpdateRequest(source.sourceType(), source.sourceBusinessKey(), source.contractId(), source.agentId(),
                source.commissionItemId(), new BigDecimal(amount), source.settlementMonth(), source.cashflowType(),
                source.scheduledPaymentDate(), source.paymentStage(), source.allocationPolicyVersion(), attributions(amount),
                source.evidenceRef(), note, token);
    }

    private List<CommissionPaymentAttributionRequest> attributions(String amount) {
        return List.of(new CommissionPaymentAttributionRequest(1L, LocalDate.of(2026, 7, 31), new BigDecimal(amount),
                InclusionDecisionStatus.INCLUDED, ExclusionType.NONE, "#753 직접 귀속", "DIRECT", "IT-753-EVIDENCE", AttributionMethod.DIRECT));
    }
}
