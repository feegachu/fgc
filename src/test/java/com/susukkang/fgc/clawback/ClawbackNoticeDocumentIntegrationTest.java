package com.susukkang.fgc.clawback;

import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.clawback.dto.ClawbackCalculationRequest;
import com.susukkang.fgc.clawback.dto.ClawbackNoticeDocumentRequest;
import com.susukkang.fgc.clawback.dto.ClawbackNoticeDocumentResponse;
import com.susukkang.fgc.clawback.service.ClawbackCalculationService;
import com.susukkang.fgc.clawback.service.ClawbackCandidateCaptureService;
import com.susukkang.fgc.clawback.service.ClawbackCandidateQueryService;
import com.susukkang.fgc.clawback.service.ClawbackNoticeDocumentService;
import com.susukkang.fgc.clawback.service.ClawbackNoticePdfRenderer;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.security.AgentDataScopeService;
import com.susukkang.fgc.common.storage.FileStorageProperties;
import com.susukkang.fgc.document.service.GeneratedDocumentService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.transaction.TestTransaction;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** FGC-FUN-084 CT-W02 / IF-API-73·86·67A: 실제 PostgreSQL과 비공개 볼륨에서 발행판·멱등·낡은 요청·동시 요청·권한·파일 증빙을 검증한다. */
@DisplayName("FGC-FUN-084 환수 통지서 PDF 발행·이력·다운로드")
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_clawback_671?currentSchema=fgc",
        "fgc.storage.root=build/tmp/fgc-storage-671"})
@AutoConfigureMockMvc
@Transactional
class ClawbackNoticeDocumentIntegrationTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;
    @Autowired private ClawbackCandidateCaptureService capture;
    @Autowired private ClawbackCandidateQueryService query;
    @Autowired private ClawbackCalculationService calculation;
    @Autowired private ClawbackNoticeDocumentService notices;
    @Autowired private GeneratedDocumentService downloads;
    @Autowired private AgentDataScopeService scope;
    @Autowired private FgcUserDetailsService users;
    @Autowired private FileStorageProperties storageProperties;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @MockitoSpyBean private ClawbackNoticePdfRenderer renderer;
    private long contractId;
    private String contractNo;
    private long agentId;
    private long settlementUser;
    private long baseItem;

    @BeforeEach
    void independentContract() {
        contractNo = "C671-" + UUID.randomUUID();
        contractId = jdbc.queryForObject("""
                INSERT INTO fgc.insurance_contract(insurer_id,product_offering_id,contract_no,contract_date,
                    agent_id,organization_id,premium_per_cycle_amount,first_premium_amount,
                    monthly_equivalent_first_premium,payment_term_months)
                SELECT insurer_id,product_offering_id,?,DATE '2026-01-01',agent_id,organization_id,
                       premium_per_cycle_amount,first_premium_amount,monthly_equivalent_first_premium,payment_term_months
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1 RETURNING contract_id
                """, Long.class, contractNo);
        agentId = jdbc.queryForObject("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id=?", Long.class, contractId);
        settlementUser = insertUser("SETTLEMENT").getUserId();
        baseItem = jdbc.queryForObject("SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'",
                Long.class);
    }

    @AfterEach
    void clearSecurityAndSpies() {
        SecurityContextHolder.clearContext();
        reset(renderer);
    }

    @Test
    void issuesThePdfFromTheCalculationSnapshotWithoutTouchingCaseState() throws Exception {
        Calculated calc = calculated("1000.4", "50");
        Map<String, Object> before = caseState(calc.caseId());

        var document = notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key(), settlementUser);

        assertThat(document.documentNo()).isEqualTo("CLN-" + calc.caseId());
        assertThat(document.version()).isEqualTo(1);
        assertThat(document.calculationVersion()).isEqualTo(1);
        assertThat(document.downloadPath()).isEqualTo("/api/v1/documents/CLN-" + calc.caseId() + ".pdf?version=1");
        var file = downloads.download(document.documentNo(), 1);
        assertThat(sha256(file.content())).isEqualTo(document.sha256());
        // 1000.4→1000 ×50% = 500. 금액·비율·정책은 계산판 snapshot 그대로다.
        String text = text(file.content());
        assertThat(text).contains("환수 통지서", document.documentNo(), contractNo, "1,000원", "50%", "500원",
                "계약 실효", "2026-04-01", calc.policyCode());
        assertThat(caseState(calc.caseId())).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.clawback_notice_delivery WHERE clawback_case_id=?",
                Long.class, calc.caseId())).isZero();
    }

    @Test
    void sameKeyReplaysTheSameVersionAndANewRequestAppendsANewVersion() {
        Calculated calc = calculated("1000", "100");
        String key = key();
        var first = notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key, settlementUser);
        byte[] firstBytes = downloads.download(first.documentNo(), 1).content();

        assertThat(notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key, settlementUser)).isEqualTo(first);
        var second = notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key(), settlementUser);
        assertThat(second.documentNo()).isEqualTo(first.documentNo());
        assertThat(second.version()).isEqualTo(2);
        assertThat(downloads.download(first.documentNo(), 1).content()).isEqualTo(firstBytes);
        assertThatThrownBy(() -> notices.issue(calc.caseId(), request(1, "other-source"), key, settlementUser))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_IDEMPOTENCY_CONFLICT));

        var history = notices.history(calc.caseId(), 1, 1);
        assertThat(history.totalElements()).isEqualTo(2);
        assertThat(history.content()).extracting(ClawbackNoticeDocumentResponse::version).containsExactly(2);
    }

    @Test
    void staleRequestsAreRejectedAndTheOldPdfStaysAsIssued() throws Exception {
        Calculated calc = calculated("1000", "100");
        var issued = notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key(), settlementUser);
        byte[] issuedBytes = downloads.download(issued.documentNo(), 1).content();

        // 계산 뒤 원천이 바뀌면 같은 계산판으로도 새 판을 만들지 않는다.
        payment("500");
        String changed = query.detail(calc.candidateId()).candidate().sourceVersion();
        assertThat(changed).isNotEqualTo(calc.sourceVersion());
        assertStale(() -> notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key(), settlementUser));
        assertStale(() -> notices.issue(calc.caseId(), request(1, changed), key(), settlementUser));

        // 다시 계산하면 2판이 최신이다. 1판을 지정한 요청은 낡았다.
        calculation.calculate(calc.candidateId(), new ClawbackCalculationRequest(changed, calc.policyId(), "원천 변경 반영"),
                key(), settlementUser);
        assertStale(() -> notices.issue(calc.caseId(), request(1, changed), key(), settlementUser));
        var reissued = notices.issue(calc.caseId(), request(2, changed), key(), settlementUser);
        assertThat(reissued.calculationVersion()).isEqualTo(2);
        assertThat(text(downloads.download(reissued.documentNo(), 2).content())).contains("1,500원");
        assertThat(downloads.download(issued.documentNo(), 1).content()).isEqualTo(issuedBytes);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.generated_document WHERE document_no=?", Long.class,
                issued.documentNo())).isEqualTo(2);
    }

    @Test
    void renderingFailureLeavesNoHistoryAndRollbackRemovesTheStoredFile() throws Exception {
        Calculated calc = calculated("1000", "100");
        doThrow(new java.io.UncheckedIOException(new IOException("render"))).when(renderer).render(any());
        assertThatThrownBy(() -> notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key(), settlementUser))
                .isInstanceOf(java.io.UncheckedIOException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM fgc.generated_document WHERE source_entity_id=? "
                + "AND document_kind='CLAWBACK_NOTICE'", Long.class, calc.caseId())).isZero();

        // 파일 저장 뒤 상위 트랜잭션이 롤백되면 원본도 지운다.
        reset(renderer);
        var document = notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key(), settlementUser);
        String storageKey = jdbc.queryForObject("SELECT storage_key FROM fgc.generated_document WHERE document_no=? "
                + "AND version_no=?", String.class, document.documentNo(), document.version());
        var file = storageProperties.root().toAbsolutePath().normalize().resolve(storageKey);
        assertThat(file).exists();
        TestTransaction.flagForRollback();
        TestTransaction.end();
        assertThat(file).doesNotExist();
    }

    @Test
    void tamperedOrMissingFilesAreNotServed() throws Exception {
        Calculated calc = calculated("1000", "100");
        var document = notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key(), settlementUser);
        String storageKey = jdbc.queryForObject("SELECT storage_key FROM fgc.generated_document WHERE document_no=?",
                String.class, document.documentNo());
        Files.write(storageProperties.root().toAbsolutePath().normalize().resolve(storageKey), new byte[]{1, 2, 3});
        assertThatThrownBy(() -> downloads.download(document.documentNo(), 1))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_500));
        assertThatThrownBy(() -> downloads.download(document.documentNo(), 2))
                .isInstanceOfSatisfying(FgcBusinessException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.COMMON_004));
    }

    @Test
    void internalRolesFollowTheRoleContractAndAgentsAreBlockedEvenAsRecipients() throws Exception {
        Calculated calc = calculated("1000", "100");
        String body = "{\"calculationVersion\":1,\"expectedSourceVersion\":\"" + calc.sourceVersion() + "\"}";
        String path = "/api/v1/clawbacks/{id}/notice-documents";

        mvc.perform(post(path, calc.caseId()).with(user(insertUser("COMPLIANCE"))).with(csrf())
                        .header("Idempotency-Key", key()).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(post(path, calc.caseId()).with(user(insertUser("GA_ADMIN"))).with(csrf())
                        .header("Idempotency-Key", key()).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version").value(1))
                .andExpect(jsonPath("$.data.storageKey").doesNotExist());
        mvc.perform(post(path, calc.caseId()).with(user(insertUser("SETTLEMENT"))).with(csrf())
                        .contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());

        var compliance = insertUser("COMPLIANCE");
        mvc.perform(get(path, calc.caseId()).with(user(compliance)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.totalElements").value(1));
        String download = "/api/v1/documents/CLN-" + calc.caseId() + ".pdf";
        mvc.perform(get(download).param("version", "1").with(user(compliance)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.startsWith("attachment")));
        mvc.perform(get(download).param("version", "9").with(user(compliance))).andExpect(status().isNotFound());
        mvc.perform(get(path, 999_999_999L).with(user(compliance))).andExpect(status().isNotFound());

        // 통지 수신 이력이 있는 설계사라도 통지서·이력·발행 모두 막는다.
        FgcUserDetails agent = (FgcUserDetails) users.loadUserByUsername("agent01");
        long documentId = jdbc.queryForObject("SELECT generated_document_id FROM fgc.generated_document WHERE document_no=?",
                Long.class, "CLN-" + calc.caseId());
        jdbc.update("""
                INSERT INTO fgc.clawback_notice_delivery(clawback_case_id, generated_document_id, recipient_agent_id,
                    notice_date, notice_kind, delivery_method, evidence_ref, registered_by)
                VALUES (?, ?, (SELECT agent_id FROM fgc.app_user WHERE login_id='agent01'), DATE '2026-05-01',
                    'FIRST', 'EMAIL', 'test', ?)
                """, calc.caseId(), documentId, settlementUser);
        mvc.perform(get(download).param("version", "1").with(user(agent))).andExpect(status().isForbidden());
        mvc.perform(get(path, calc.caseId()).with(user(agent))).andExpect(status().isForbidden());
        mvc.perform(post(path, calc.caseId()).with(user(agent)).with(csrf())
                        .header("Idempotency-Key", key()).contentType(APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                agent, null, agent.getAuthorities()));
        String documentNo = "CLN-" + calc.caseId();
        assertThatThrownBy(() -> scope.requireDocument(documentNo, 1))
                .isInstanceOf(FgcBusinessException.class);
    }

    @Test
    @DisplayName("FGC-FUN-084 계산판이 없는 case는 422 FGC-CLAW-001이고 판을 만들지 않는다")
    void uncalculatedCaseIsRejectedAsNotCalculated() throws Exception {
        long caseId = jdbc.queryForObject("""
                INSERT INTO fgc.clawback_case(contract_id, policy_version_id, case_no)
                VALUES (?, (SELECT min(policy_version_id) FROM fgc.policy_version), ?) RETURNING clawback_case_id
                """, Long.class, contractId, "C671-" + UUID.randomUUID());

        assertThatThrownBy(() -> notices.issue(caseId, request(1, "source-v1"), key(), settlementUser))
                .isInstanceOfSatisfying(FgcBusinessException.class, e -> {
                    assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.CLAW_001);
                    assertThat(e.getField()).isEqualTo("calculationVersion");
                });
        mvc.perform(post("/api/v1/clawbacks/{id}/notice-documents", caseId).with(user(insertUser("SETTLEMENT")))
                        .with(csrf()).header("Idempotency-Key", key()).contentType(APPLICATION_JSON)
                        .content("{\"calculationVersion\":1,\"expectedSourceVersion\":\"source-v1\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FGC-CLAW-001"));
        mvc.perform(post("/api/v1/clawbacks/{id}/notice-documents", 999_999_999L).with(user(insertUser("SETTLEMENT")))
                        .with(csrf()).header("Idempotency-Key", key()).contentType(APPLICATION_JSON)
                        .content("{\"calculationVersion\":1,\"expectedSourceVersion\":\"source-v1\"}"))
                .andExpect(status().isNotFound());
        assertThat(noticeCount(caseId)).isZero();
    }

    @Test
    @DisplayName("FGC-FUN-084 snapshot에 소수 금액이 있으면 반올림으로 가리지 않고 렌더링이 실패한다")
    void fractionalSnapshotAmountsFailRendering() {
        var line = new ClawbackNoticePdfRenderer.Line("기본수수료", 1, new BigDecimal("1000.5"), new BigDecimal("50"),
                new BigDecimal("500"));
        var notice = new ClawbackNoticePdfRenderer.Notice("CLN-1", 1, OffsetDateTime.now(), "C-1", "보험사", "상품",
                "A-1", "설계사", "계약 실효", LocalDate.of(2026, 4, 1), LocalDate.of(2026, 1, 1), "정책", 1,
                OffsetDateTime.now(), 3, "HALF_UP", List.of(line), new BigDecimal("500"));
        assertThatThrownBy(() -> renderer.render(notice)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    @DisplayName("FGC-FUN-084 같은 키 요청이 처리 중이면 409 REQUEST_IN_PROGRESS이고 판을 만들지 않는다")
    void sameKeyInProgressIsRejected() throws Exception {
        Calculated calc = calculated("1000", "100");
        String key = key();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                lock.setString(1, "clawback-notice-request:" + key);
                lock.execute();
            }
            assertThatThrownBy(() -> notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key, settlementUser))
                    .isInstanceOfSatisfying(FgcBusinessException.class,
                            e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_REQUEST_IN_PROGRESS));
            other.rollback();
        }
        assertThat(noticeCount(calc.caseId())).isZero();
    }

    @Test
    @Timeout(60)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("FGC-FUN-084 같은 case에 다른 키로 동시에 발행하면 1판·2판이 빈틈없이 쌓인다")
    void concurrentIssuesWithDifferentKeysAppendConsecutiveVersions() throws Exception {
        Calculated calc = calculated("1000", "100");
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<Integer> task = () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key(), settlementUser).version();
            };
            var first = workers.submit(task);
            var second = workers.submit(task);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(1, 2);
        }
        assertThat(jdbc.queryForList("SELECT version_no FROM fgc.generated_document WHERE document_no=? ORDER BY version_no",
                Integer.class, "CLN-" + calc.caseId())).containsExactly(1, 2);
    }

    @Test
    @Timeout(60)
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    @DisplayName("FGC-FUN-084 재계산(IF85)이 case를 잡고 있으면 발행은 기다렸다가 낡은 판 요청으로 409가 된다")
    void issueWaitsForAConcurrentRecalculationAndRejectsTheOldVersion() throws Exception {
        // 정책 후보도 원천 버전에 들어가므로 두 번째 정책을 먼저 만든 뒤 1판을 계산한다.
        long otherPolicy = policy("C671-" + UUID.randomUUID(), "50");
        Calculated calc = calculated("1000", "100");
        var calculated = new CountDownLatch(1);
        var commit = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            // 다른 정책으로 다시 계산하면 같은 원천이라도 2판이 쌓인다. 커밋 전까지 case 행 잠금을 쥐고 있다.
            var recalculation = workers.submit(() -> new TransactionTemplate(transactionManager).execute(tx -> {
                var result = calculation.calculate(calc.candidateId(),
                        new ClawbackCalculationRequest(calc.sourceVersion(), otherPolicy, "동시 재계산"), key(), settlementUser);
                calculated.countDown();
                try { assertThat(commit.await(15, TimeUnit.SECONDS)).isTrue(); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                return result.calculationVersion();
            }));
            try {
                // 재계산이 먼저 실패하면 그 원인을 그대로 드러낸다.
                if (!calculated.await(15, TimeUnit.SECONDS)) recalculation.get(1, TimeUnit.SECONDS);
                var issue = workers.submit(() ->
                        notices.issue(calc.caseId(), request(1, calc.sourceVersion()), key(), settlementUser));
                await().alias("발행이 재계산의 case 행 잠금을 기다린다")
                        .pollInterval(Duration.ofMillis(10)).atMost(Duration.ofSeconds(10))
                        .until(() -> Boolean.TRUE.equals(jdbc.queryForObject(
                                "SELECT EXISTS (SELECT 1 FROM pg_locks WHERE NOT granted)", Boolean.class)));
                assertThat(issue.isDone()).isFalse();
                commit.countDown();
                assertThat(recalculation.get(15, TimeUnit.SECONDS)).isEqualTo(2);
                assertThatThrownBy(() -> issue.get(15, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .cause().isInstanceOfSatisfying(FgcBusinessException.class,
                                e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_STATE_CHANGED));
            } finally {
                commit.countDown();
            }
        }
        assertThat(noticeCount(calc.caseId())).isZero();
    }

    private long noticeCount(long caseId) {
        return jdbc.queryForObject("SELECT count(*) FROM fgc.generated_document WHERE source_entity_id=? "
                + "AND document_kind='CLAWBACK_NOTICE'", Long.class, caseId);
    }

    private record Calculated(long candidateId, long caseId, String sourceVersion, long policyId, String policyCode) { }

    /** 1건 지급·환수율 하나인 정책으로 계산판 1판까지 만든다. */
    private Calculated calculated(String paidAmount, String ratePct) {
        String policyCode = "C671-" + UUID.randomUUID();
        long policy = policy(policyCode, ratePct);
        payment(paidAmount);
        long event = jdbc.queryForObject("""
                INSERT INTO fgc.contract_status_event(contract_id,event_seq,new_status,effective_at,received_at,
                    source_system,source_event_key,source_version)
                SELECT ?,COALESCE(MAX(event_seq),0)+1,'LAPSED',TIMESTAMPTZ '2026-04-01T00:00:00+09:00',now(),'TEST-671',?,'source-v1'
                  FROM fgc.contract_status_event WHERE contract_id=? RETURNING contract_status_event_id
                """, Long.class, contractId, UUID.randomUUID().toString(), contractId);
        capture.captureStatusEvents(List.of(event));
        long candidate = jdbc.queryForObject("SELECT candidate_id FROM fgc.clawback_candidate WHERE status_event_id=?",
                Long.class, event);
        String source = query.detail(candidate).candidate().sourceVersion();
        var result = calculation.calculate(candidate, new ClawbackCalculationRequest(source, policy, "통지서 검증 계산"),
                key(), settlementUser);
        return new Calculated(candidate, result.clawbackCaseId(), source, policy, policyCode);
    }

    private long policy(String code, String ratePct) {
        long id = jdbc.queryForObject("""
                INSERT INTO fgc.policy_version(policy_code,policy_name,policy_type,source_class,version_no,
                    effective_from,status,created_by)
                VALUES (?, '통지서 검증 정책', 'CLAWBACK','GA_POLICY',1,DATE '2020-01-01','DRAFT',
                    (SELECT min(user_id) FROM fgc.app_user)) RETURNING policy_version_id
                """, Long.class, code);
        jdbc.update("""
                INSERT INTO fgc.clawback_rule(policy_version_id,commission_item_id,elapsed_month_from,
                    elapsed_month_to,clawback_basis_code,clawback_rate_pct)
                VALUES (?,?,0,1199,'PAID_ATTRIBUTED_AMOUNT',?)
                """, id, baseItem, new BigDecimal(ratePct));
        jdbc.update("""
                UPDATE fgc.policy_version SET status='APPROVED', approved_at=now(),
                    approved_by=(SELECT max(user_id) FROM fgc.app_user) WHERE policy_version_id=?
                """, id);
        jdbc.update("UPDATE fgc.policy_version SET status='ACTIVE' WHERE policy_version_id=?", id);
        return id;
    }

    private void payment(String amount) {
        long tx = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction(payment_stage,source_type,source_business_key,insurer_id,
                    recipient_agent_id,commission_item_id,settlement_month,paid_on,amount,cashflow_type,installment_no)
                SELECT 'GA_TO_FC','GA_MANUAL_PAYMENT',?,insurer_id,?,?,DATE '2026-03-01',DATE '2026-03-01',?,'PAYMENT',1
                  FROM fgc.insurance_contract WHERE contract_id=? RETURNING commission_transaction_id
                """, Long.class, UUID.randomUUID().toString(), agentId, baseItem, new BigDecimal(amount), contractId);
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id,contract_id,agent_id,
                    attribution_date,attribution_month,attributed_amount,inclusion_status_snapshot,attribution_method)
                VALUES (?,?,?,DATE '2026-03-01',DATE '2026-03-01',?,'INCLUDED','DIRECT')
                """, tx, contractId, agentId, new BigDecimal(amount));
        jdbc.update("UPDATE fgc.commission_transaction SET status='CONFIRMED' WHERE commission_transaction_id=?", tx);
    }

    private Map<String, Object> caseState(long caseId) {
        return jdbc.queryForMap("""
                SELECT status, notice_date, confirmed_amount, calculated_amount, updated_at
                  FROM fgc.clawback_case WHERE clawback_case_id=?
                """, caseId);
    }

    private void assertStale(org.assertj.core.api.ThrowableAssert.ThrowingCallable call) {
        assertThatThrownBy(call).isInstanceOfSatisfying(FgcBusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(FgcErrorCode.IMPL_STATE_CHANGED));
    }

    private static ClawbackNoticeDocumentRequest request(int calculationVersion, String sourceVersion) {
        return new ClawbackNoticeDocumentRequest(calculationVersion, sourceVersion);
    }

    private static String text(byte[] pdf) throws IOException {
        try (var document = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String sha256(byte[] content) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    }

    private static String key() {
        return "claw-notice-" + UUID.randomUUID();
    }

    private FgcUserDetails insertUser(String role) {
        String login = "c671-" + UUID.randomUUID();
        Long id = jdbc.queryForObject("""
                INSERT INTO fgc.app_user(login_id, password_hash, user_name, role_id)
                VALUES (?, 'unused', '통지서 검증 사용자', (SELECT role_id FROM fgc.app_role WHERE role_code = ?))
                RETURNING user_id
                """, Long.class, login, role);
        return new FgcUserDetails(new AppUserView(id, login, "unused", "통지서 검증 사용자", role, "ACTIVE"), true, true);
    }
}
