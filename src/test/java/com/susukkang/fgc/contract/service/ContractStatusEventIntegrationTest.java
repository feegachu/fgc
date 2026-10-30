package com.susukkang.fgc.contract.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.PaymentCycleCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractStatusEffect;
import com.susukkang.fgc.contract.dto.ContractStatusEventRequest;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.entity.ContractStatusEvent;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.ContractEventJobRepository;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.susukkang.fgc.common.code.ContractStatus.ACTIVE;
import static com.susukkang.fgc.common.code.ContractStatus.LAPSED;
import static com.susukkang.fgc.common.code.ContractStatus.TERMINATED;
import static com.susukkang.fgc.common.code.ContractStatus.UNPAID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FUN-025·026(#653·#625) 인수 시험 CT-C01과 후속 작업 계약을 실제 PostgreSQL·HTTP 경로로 검증한다.
 * 요청이 커밋되므로 전용 DB를 쓰고, 테스트용 처리기로 FUN-028·029 처리기 자리를 대신한다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_status_event?currentSchema=fgc")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Timeout(60)
class ContractStatusEventIntegrationTest {

    private static final OffsetDateTime AUG_1 = OffsetDateTime.parse("2026-08-01T00:00:00+09:00");
    private static final String CODE = "MANUAL_STATUS_CHANGE";
    private static final String REASON = "보험회사 통보 반영";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ContractService contracts;
    @Autowired private InsuranceContractRepository contractRepository;
    @Autowired private ContractStatusFollowUpService followUps;
    @Autowired private FgcUserDetailsService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private DataSource dataSource;
    @Autowired private PlatformTransactionManager transactionManager;
    @Autowired private RecordingProcessor processor;
    @MockitoSpyBean private ContractEventJobRepository jobRepository;

    private FgcUserDetails settle;

    @BeforeEach
    void setUp() {
        settle = (FgcUserDetails) users.loadUserByUsername("settle01");
        processor.reset();
        // 앞 테스트가 남긴 대기 작업을 비워 이 테스트가 만든 작업의 처리 순서만 본다.
        followUps.runDueJobs();
        processor.processed.clear();
    }

    @Test
    @DisplayName("CT-C01 일반 수정은 상태 사건·후속 작업을 만들지 않는다")
    void generalUpdateCreatesNoStatusEvent() throws Exception {
        Long id = newContract();
        ContractUpdateRequest body = update(id, ACTIVE, null);
        body.setContractNo(body.getContractNo() + "-R");

        put(id, body, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.statusEventId").doesNotExist())
                .andExpect(jsonPath("$.data.followUp.status").value("NOT_REQUIRED"))
                .andExpect(jsonPath("$.data.followUp.eventId").doesNotExist());

        assertThat(events(id)).isEqualTo(1);
        assertThat(jobs(id)).isZero();
    }

    @Test
    @DisplayName("CT-C01 상태 변경은 사건 1건·대기 작업을 남기고 스케줄을 재생성하지 않으며 이력에 사유·등록자를 보여 준다")
    void statusChangeSavesOneEventWithoutRegeneratingSchedules() throws Exception {
        Long id = newContract();
        processor.supported = EnumSet.of(UNPAID);
        List<Long> headers = scheduleHeaders(id);
        String key = UUID.randomUUID().toString();

        JsonNode data = data(put(id, update(id, UNPAID, event(AUG_1)), key)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.regeneratedScheduleIds").isEmpty())
                .andExpect(jsonPath("$.data.followUp.status").value("PENDING")));
        long eventId = data.path("statusEventId").asLong();

        assertThat(data.path("followUp").path("eventId").asLong()).isEqualTo(eventId);
        assertThat(OffsetDateTime.parse(data.path("updatedAt").asText()).toInstant())
                .isEqualTo(contractRepository.findById(id).orElseThrow().getUpdatedAt().toInstant());
        Map<String, Object> event = jdbc.queryForMap("""
                SELECT event_seq, previous_status, new_status, source_system, source_event_key, reason_code,
                       reason_text, registered_by, data_origin
                  FROM fgc.contract_status_event WHERE contract_status_event_id = ?
                """, eventId);
        assertThat(event).containsEntry("event_seq", 2).containsEntry("previous_status", "ACTIVE")
                .containsEntry("new_status", "UNPAID").containsEntry("source_system", "FGC_MANUAL")
                .containsEntry("source_event_key", key).containsEntry("reason_code", CODE)
                .containsEntry("reason_text", REASON).containsEntry("registered_by", settle.getUserId())
                .containsEntry("data_origin", "MANUAL");
        assertThat(contractStatus(id)).isEqualTo("UNPAID");
        assertThat(scheduleHeaders(id)).isEqualTo(headers);
        assertThat(jobState(eventId)).isEqualTo("PENDING");

        // IF-API-16: 수기 사건은 사유·등록자를 보여 주고, 등록자가 없는 최초 사건을 현재 사용자로 채우지 않는다.
        mvc.perform(get("/api/v1/contracts/{id}/status-events", id).with(user(settle)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].registeredBy").doesNotExist())
                .andExpect(jsonPath("$.data[1].eventId").value(eventId))
                .andExpect(jsonPath("$.data[1].reasonCode").value(CODE))
                .andExpect(jsonPath("$.data[1].reason").value(REASON))
                .andExpect(jsonPath("$.data[1].registeredBy.loginId").value("settle01"));
        // CONT-W03 선택지는 서버 전이 표와 같다.
        mvc.perform(get("/api/v1/contracts/{id}", id).with(user(settle)))
                .andExpect(jsonPath("$.data.allowedNextStatuses",
                        containsInAnyOrder("ACTIVE", "LAPSED", "TERMINATED", "MATURED", "CANCELLED")))
                .andExpect(jsonPath("$.data.statusReasonCodes", containsInAnyOrder(CODE)));
    }

    @Test
    @DisplayName("CT-C01 같은 키·같은 요청은 처음 결과를 돌려주고 중복을 만들지 않으며, 같은 키·다른 요청은 409다")
    void sameKeyReplayReturnsFirstResult() throws Exception {
        Long id = newContract();
        processor.supported = EnumSet.of(UNPAID);
        ContractUpdateRequest body = update(id, UNPAID, event(AUG_1));
        String key = UUID.randomUUID().toString();

        JsonNode first = data(put(id, body, key).andExpect(status().isOk()));
        JsonNode again = data(put(id, body, key).andExpect(status().isOk()));

        assertThat(again).isEqualTo(first);
        assertThat(events(id)).isEqualTo(2);
        assertThat(jobs(id)).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM fgc.contract_write_request WHERE idempotency_key = ?", key))
                .isEqualTo(1);

        put(id, update(id, LAPSED, event(AUG_1.plusDays(1))), key)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-IDEMPOTENCY_CONFLICT"));
        assertThat(events(id)).isEqualTo(2);
        assertThat(contractStatus(id)).isEqualTo("UNPAID");
    }

    @Test
    @DisplayName("같은 키 요청이 처리 중이면 409 REQUEST_IN_PROGRESS이고 아무것도 저장하지 않는다")
    void sameKeyInProgressIsRejected() throws Exception {
        Long id = newContract();
        String key = UUID.randomUUID().toString();

        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement lock = other.prepareStatement("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")) {
                lock.setString(1, "contract-request:" + key);
                lock.execute();
            }
            put(id, update(id, UNPAID, event(AUG_1)), key)
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("FGC-IMPL-REQUEST_IN_PROGRESS"));
            other.rollback();
        }
        assertThat(events(id)).isEqualTo(1);
        assertThat(contractStatus(id)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("CT-C01 허용 전이 표에 없는 전이는 409이고 계약·사건·스케줄을 바꾸지 않는다(해지→정상 포함)")
    void deniedTransitionsChangeNothing() throws Exception {
        Long id = newContract();
        List<Long> headers = scheduleHeaders(id);

        // 정상→실효: 미납을 거쳐야 한다(운영정책서 제13조의2 2항)
        put(id, update(id, LAPSED, event(AUG_1)), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-TRANSITION_DENIED"));
        assertThat(contractStatus(id)).isEqualTo("ACTIVE");
        assertThat(events(id)).isEqualTo(1);
        assertThat(scheduleHeaders(id)).isEqualTo(headers);
        assertThat(count("SELECT count(*) FROM fgc.contract_write_request WHERE contract_id = ?", id)).isZero();

        put(id, update(id, TERMINATED, event(AUG_1)), UUID.randomUUID().toString()).andExpect(status().isOk());
        put(id, update(id, ACTIVE, event(AUG_1.plusDays(1))), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.message")
                        .value("저장 불가 — 해지에서 정상(으)로 바꿀 수 없습니다. 계약을 다시 조회해 허용된 상태를 고르세요."));
        assertThat(contractStatus(id)).isEqualTo("TERMINATED");
        assertThat(events(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("CT-C01 최초 설계사 변경은 422, 오래된 expectedUpdatedAt은 409이며 아무것도 저장하지 않는다")
    void agentChangeAndStaleTokenAreRejected() throws Exception {
        Long id = newContract();
        ContractUpdateRequest otherAgent = update(id, ACTIVE, null);
        Long originalAgent = otherAgent.getAgentId();
        otherAgent.setAgentId(jdbc.queryForObject(
                "SELECT min(agent_id) FROM fgc.agent WHERE agent_id <> ?", Long.class, originalAgent));

        put(id, otherAgent, null)
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-AGENT_FIXED"));

        ContractUpdateRequest stale = update(id, UNPAID, event(AUG_1));
        stale.setExpectedUpdatedAt(stale.getExpectedUpdatedAt().minusSeconds(1));
        put(id, stale, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-CONT-003"));

        assertThat(contractRepository.findById(id).orElseThrow().getAgentId()).isEqualTo(originalAgent);
        assertThat(contractStatus(id)).isEqualTo("ACTIVE");
        assertThat(events(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("D-03 일자 범위: 최신 사건보다 이른 효력·접수보다 늦은 효력·미래 접수는 422, 최신 사건과 같은 효력일시는 받는다")
    void eventDatesOutsideD03RangeAreRejected() throws Exception {
        Long id = newContract();
        OffsetDateTime latest = jdbc.queryForObject(
                "SELECT max(effective_at) FROM fgc.contract_status_event WHERE contract_id = ?", OffsetDateTime.class, id);
        OffsetDateTime now = DateUtil.nowSeoul();

        assertEventOrder(id, new ContractStatusEventRequest(latest.minusSeconds(1), now.minusMinutes(1), CODE, REASON),
                "statusEvent.effectiveAt");
        assertEventOrder(id, new ContractStatusEventRequest(now.minusMinutes(1), now.minusMinutes(2), CODE, REASON),
                "statusEvent.effectiveAt");
        assertEventOrder(id, new ContractStatusEventRequest(AUG_1, now.plusMinutes(5), CODE, REASON),
                "statusEvent.receivedAt");
        assertThat(events(id)).isEqualTo(1);

        put(id, update(id, UNPAID, new ContractStatusEventRequest(latest, now.minusMinutes(1), CODE, REASON)),
                UUID.randomUUID().toString()).andExpect(status().isOk());
        assertThat(events(id)).isEqualTo(2);
    }

    @Test
    @DisplayName("상태 사건 입력 검증: 사건 누락·상태 미변경 사건·멱등키 누락/형식·사유코드·자유 사유 길이")
    void statusEventInputIsValidated() throws Exception {
        Long id = newContract();
        OffsetDateTime received = DateUtil.nowSeoul().minusMinutes(1);
        String key = UUID.randomUUID().toString();

        assertInvalid(id, update(id, UNPAID, null), key, "statusEvent");
        assertInvalid(id, update(id, ACTIVE, event(AUG_1)), key, "statusEvent");
        assertInvalid(id, update(id, UNPAID, event(AUG_1)), null, "Idempotency-Key");
        assertInvalid(id, update(id, UNPAID, event(AUG_1)), "not-a-uuid", "Idempotency-Key");
        assertInvalid(id, update(id, UNPAID, new ContractStatusEventRequest(AUG_1, received, "OTHER", REASON)),
                key, "statusEvent.reasonCode");
        assertInvalid(id, update(id, UNPAID, new ContractStatusEventRequest(AUG_1, received, CODE, " ")),
                key, "statusEvent.reason");
        assertInvalid(id, update(id, UNPAID, new ContractStatusEventRequest(AUG_1, received, CODE, "가".repeat(2001))),
                key, "statusEvent.reason");

        assertThat(events(id)).isEqualTo(1);
        assertThat(contractStatus(id)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("저장 중 예외가 나면 계약·사건·후속 작업·멱등 결과·감사가 모두 원복된다")
    void failureInsideSaveRollsBackEverything() throws Exception {
        Long id = newContract();
        processor.failOnSupports = true;
        String key = UUID.randomUUID().toString();

        put(id, update(id, UNPAID, event(AUG_1)), key).andExpect(status().isInternalServerError());

        assertThat(contractStatus(id)).isEqualTo("ACTIVE");
        assertThat(events(id)).isEqualTo(1);
        assertThat(jobs(id)).isZero();
        assertThat(count("SELECT count(*) FROM fgc.contract_write_request WHERE idempotency_key = ?", key)).isZero();
        assertThat(count("""
                SELECT count(*) FROM fgc.audit_log
                 WHERE action_code = 'CONTRACT_UPDATED' AND entity_type = 'CONTRACT' AND entity_id = ?
                """, String.valueOf(id))).isZero();
    }

    @Test
    @DisplayName("커밋된 대기 작업은 나중에 worker가 처리하고 영향·성공 이력은 한 번만 남는다")
    void committedJobIsProcessedOnceLater() throws Exception {
        Long id = newContract();
        processor.supported = EnumSet.of(UNPAID);
        long eventId = data(put(id, update(id, UNPAID, event(AUG_1)), UUID.randomUUID().toString())
                .andExpect(status().isOk())).path("statusEventId").asLong();

        // 커밋 직후 프로세스가 멈춘 상황: 메모리 이벤트 없이 DB의 PENDING 행만 남아 있다.
        assertThat(jobState(eventId)).isEqualTo("PENDING");
        assertThat(processor.processed).isEmpty();

        followUps.runDueJobs();
        assertThat(jobState(eventId)).isEqualTo("SUCCEEDED");
        assertThat(effects(eventId)).isEqualTo(1);
        assertThat(history(eventId, "SUCCEEDED")).isEqualTo(1);

        followUps.runDueJobs();
        assertThat(processor.processed).containsExactly(eventId);
        assertThat(effects(eventId)).isEqualTo(1);
        assertThat(history(eventId, "SUCCEEDED")).isEqualTo(1);
    }

    @Test
    @DisplayName("후속 실패는 저장된 계약·사건을 되돌리지 않고 실패 이력을 남긴 뒤 재시도하며, 같은 계약의 다음 사건은 앞 사건을 기다린다")
    void failedJobRetriesInEventOrder() throws Exception {
        Long id = newContract();
        processor.supported = EnumSet.allOf(ContractStatus.class);
        processor.failuresLeft.set(1);
        long unpaid = data(put(id, update(id, UNPAID, event(AUG_1)), UUID.randomUUID().toString())
                .andExpect(status().isOk())).path("statusEventId").asLong();
        long paid = data(put(id, update(id, ACTIVE, event(AUG_1.plusDays(3))), UUID.randomUUID().toString())
                .andExpect(status().isOk())).path("statusEventId").asLong();

        followUps.runDueJobs();

        assertThat(processor.processed).containsExactly(unpaid);
        assertThat(jobState(unpaid)).isEqualTo("FAILED");
        assertThat(jobState(paid)).isEqualTo("PENDING");
        assertThat(contractStatus(id)).isEqualTo("ACTIVE");
        assertThat(history(unpaid, "FAILED")).isEqualTo(1);
        Map<String, Object> failed = jdbc.queryForMap("""
                SELECT attempt_count, error_code, next_retry_at > clock_timestamp() AS waiting
                  FROM fgc.contract_event_job WHERE contract_status_event_id = ?
                """, unpaid);
        assertThat(failed).containsEntry("attempt_count", 1).containsEntry("error_code", "IllegalStateException")
                .containsEntry("waiting", true);

        // 재시도 시각이 지나면 앞 사건부터 처리한다. 실패 이력은 지우지 않는다.
        jdbc.update("UPDATE fgc.contract_event_job SET next_retry_at = clock_timestamp() WHERE contract_status_event_id = ?",
                unpaid);
        followUps.runDueJobs();

        assertThat(processor.processed).containsExactly(unpaid, unpaid, paid);
        assertThat(jobState(unpaid)).isEqualTo("SUCCEEDED");
        assertThat(jobState(paid)).isEqualTo("SUCCEEDED");
        assertThat(history(unpaid, "FAILED")).isEqualTo(1);
        assertThat(history(unpaid, "SUCCEEDED")).isEqualTo(1);
    }

    @Test
    @DisplayName("실패를 기록하는 사이에도 다른 worker는 같은 작업(재시도 시각 전)과 같은 계약의 뒤 사건(순서)을 잡지 않는다")
    void concurrentWorkerWaitsWhileAFailureIsRecorded() throws Exception {
        Long id = newContract();
        processor.supported = EnumSet.allOf(ContractStatus.class);
        processor.failuresLeft.set(1);
        long unpaid = data(put(id, update(id, UNPAID, event(AUG_1)), UUID.randomUUID().toString())
                .andExpect(status().isOk())).path("statusEventId").asLong();
        long paid = data(put(id, update(id, ACTIVE, event(AUG_1.plusDays(3))), UUID.randomUUID().toString())
                .andExpect(status().isOk())).path("statusEventId").asLong();
        CountDownLatch recording = new CountDownLatch(1);
        CountDownLatch resume = new CountDownLatch(1);
        doAnswer(invocation -> {
            recording.countDown();
            assertThat(resume.await(20, TimeUnit.SECONDS)).isTrue();
            return invocation.callRealMethod();
        }).when(jobRepository).lockForFailure(any());

        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = worker.submit(followUps::runDueJobs);
            assertThat(recording.await(20, TimeUnit.SECONDS)).isTrue();
            // 첫 worker는 처리에 실패해 롤백했고 아직 실패를 기록하지 않았다.
            assertThat(followUps.runDueJobs()).isZero();
            assertThat(processor.processed).containsExactly(unpaid);
            resume.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(1);
        } finally {
            resume.countDown();
            worker.shutdownNow();
        }

        assertThat(followUps.runDueJobs()).isZero();
        assertThat(jobState(unpaid)).isEqualTo("FAILED");
        assertThat(jobState(paid)).isEqualTo("PENDING");
        assertThat(count("SELECT attempt_count FROM fgc.contract_event_job WHERE contract_status_event_id = ?", unpaid))
                .isEqualTo(1);
        assertThat(history(unpaid, "FAILED")).isEqualTo(1);
        assertThat(history(unpaid, "SUCCEEDED")).isZero();
    }

    @Test
    @DisplayName("작업을 잡은 뒤 프로세스가 멈추면 선점한 재시도 시각 전에는 잡히지 않고, 그 뒤 다시 처리되며 시도 횟수에 들어간다")
    void abandonedClaimIsRetriedAfterItsRetryTime() throws Exception {
        Long id = newContract();
        processor.supported = EnumSet.of(UNPAID);
        long eventId = data(put(id, update(id, UNPAID, event(AUG_1)), UUID.randomUUID().toString())
                .andExpect(status().isOk())).path("statusEventId").asLong();

        // 잡기만 커밋하고 처리 전에 멈춘 worker
        Optional<ContractEventJobRepository.JobKey> abandoned = new TransactionTemplate(transactionManager)
                .execute(tx -> jobRepository.claimNext(ContractStatusFollowUpService.MAX_ATTEMPTS));
        assertThat(abandoned).isPresent();
        assertThat(followUps.runDueJobs()).isZero();
        assertThat(processor.processed).isEmpty();

        jdbc.update("UPDATE fgc.contract_event_job SET next_retry_at = clock_timestamp() WHERE contract_status_event_id = ?",
                eventId);
        followUps.runDueJobs();

        assertThat(processor.processed).containsExactly(eventId);
        assertThat(jobState(eventId)).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT attempt_count FROM fgc.contract_event_job WHERE contract_status_event_id = ?", eventId))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("재시도 한도를 다 쓴 작업은 자동으로 다시 잡지 않는다")
    void exhaustedJobIsNotClaimed() throws Exception {
        Long id = newContract();
        processor.supported = EnumSet.of(UNPAID);
        long eventId = data(put(id, update(id, UNPAID, event(AUG_1)), UUID.randomUUID().toString())
                .andExpect(status().isOk())).path("statusEventId").asLong();
        jdbc.update("""
                UPDATE fgc.contract_event_job
                   SET state = 'FAILED', attempt_count = ?, next_retry_at = clock_timestamp()
                 WHERE contract_status_event_id = ?
                """, ContractStatusFollowUpService.MAX_ATTEMPTS, eventId);

        followUps.runDueJobs();

        assertThat(processor.processed).isEmpty();
        assertThat(jobState(eventId)).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("CT-C01 같은 조회 시각으로 동시에 상태를 바꾸면 하나만 저장되고 나머지는 409다")
    void concurrentStatusChangesLetOnlyOneWin() throws Exception {
        Long id = newContract();
        List<ContractUpdateRequest> bodies = List.of(update(id, UNPAID, event(AUG_1)), update(id, TERMINATED, event(AUG_1)));
        CyclicBarrier start = new CyclicBarrier(bodies.size());
        ExecutorService pool = Executors.newFixedThreadPool(bodies.size());
        List<Integer> statuses = new ArrayList<>();
        try {
            List<Future<Integer>> results = bodies.stream().map(body -> pool.submit(() -> {
                start.await();
                return put(id, body, UUID.randomUUID().toString()).andReturn().getResponse().getStatus();
            })).toList();
            for (Future<Integer> result : results) {
                statuses.add(result.get(30, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(statuses).containsExactlyInAnyOrder(200, 409);
        assertThat(events(id)).isEqualTo(2);
    }

    private Long newContract() {
        InsuranceContract seed = contractRepository.findById(jdbc.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL01-202607-0001'",
                Long.class)).orElseThrow();
        return contracts.createContract(new ContractCreateRequest(seed.getInsurerId(), "CSE-" + UUID.randomUUID(),
                seed.getProductOfferingId(), seed.getContractDate(), ACTIVE, seed.getAgentId(), seed.getOrganizationId(),
                PaymentCycleCode.MONTHLY, new BigDecimal("100000"), new BigDecimal("100000"), new BigDecimal("100000"),
                120, BigDecimal.ZERO)).contractId();
    }

    private ContractUpdateRequest update(Long id, ContractStatus status, ContractStatusEventRequest event) {
        InsuranceContract current = contractRepository.findById(id).orElseThrow();
        return new ContractUpdateRequest(current.getContractNo(), current.getInsurerId(),
                current.getProductOfferingId(), current.getContractDate(), status, current.getAgentId(),
                current.getOrganizationId(), current.getPaymentCycleCode(), current.getPremiumPerCycleAmount(),
                current.getFirstPremiumAmount(), current.getMonthlyEquivalentFirstPremium(),
                current.getPaymentTermMonths(), current.getStandardSurrenderDeductionAmount(),
                current.getUpdatedAt(), event);
    }

    private static ContractStatusEventRequest event(OffsetDateTime effectiveAt) {
        return new ContractStatusEventRequest(effectiveAt, DateUtil.nowSeoul().minusMinutes(1), CODE, REASON);
    }

    private ResultActions put(Long id, ContractUpdateRequest body, String key) throws Exception {
        MockHttpServletRequestBuilder request = MockMvcRequestBuilders.put("/api/v1/contracts/{id}", id)
                .with(user(settle)).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
        return mvc.perform(key == null ? request : request.header("Idempotency-Key", key));
    }

    private JsonNode data(ResultActions result) throws Exception {
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private void assertEventOrder(Long id, ContractStatusEventRequest event, String field) throws Exception {
        put(id, update(id, UNPAID, event), UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-EVENT_ORDER"))
                .andExpect(jsonPath("$.error.field").value(field));
    }

    private void assertInvalid(Long id, ContractUpdateRequest body, String key, String field) throws Exception {
        put(id, body, key)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value(field));
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }

    private int events(Long id) {
        return count("SELECT count(*) FROM fgc.contract_status_event WHERE contract_id = ?", id);
    }

    private int jobs(Long id) {
        return count("""
                SELECT count(*) FROM fgc.contract_event_job j
                  JOIN fgc.contract_status_event e USING (contract_status_event_id)
                 WHERE e.contract_id = ?
                """, id);
    }

    private int effects(long eventId) {
        return count("SELECT count(*) FROM fgc.contract_status_event_effect WHERE contract_status_event_id = ?", eventId);
    }

    private int history(long eventId, String processingStatus) {
        return count("""
                SELECT count(*) FROM fgc.contract_status_event_processing
                 WHERE contract_status_event_id = ? AND processing_job = ? AND processing_status = ?
                """, eventId, RecordingProcessor.JOB, processingStatus);
    }

    private String jobState(long eventId) {
        return jdbc.queryForObject("SELECT state FROM fgc.contract_event_job WHERE contract_status_event_id = ?",
                String.class, eventId);
    }

    private String contractStatus(Long id) {
        return jdbc.queryForObject("SELECT current_status FROM fgc.insurance_contract WHERE contract_id = ?",
                String.class, id);
    }

    private List<Long> scheduleHeaders(Long id) {
        return jdbc.queryForList("SELECT schedule_header_id FROM fgc.schedule_header WHERE contract_id = ? ORDER BY 1",
                Long.class, id);
    }

    @TestConfiguration
    static class ProcessorConfig {
        @Bean
        RecordingProcessor recordingProcessor() {
            return new RecordingProcessor();
        }
    }

    /** FUN-028·029 처리기 자리를 대신해 호출 순서를 기록하고 실패를 주입한다. */
    static class RecordingProcessor implements ContractStatusFollowUpProcessor {
        static final String JOB = "TestHoldJob";
        final List<Long> processed = new CopyOnWriteArrayList<>();
        final AtomicInteger failuresLeft = new AtomicInteger();
        volatile Set<ContractStatus> supported = EnumSet.noneOf(ContractStatus.class);
        volatile boolean failOnSupports;

        void reset() {
            processed.clear();
            failuresLeft.set(0);
            supported = EnumSet.noneOf(ContractStatus.class);
            failOnSupports = false;
        }

        @Override
        public String jobName() {
            return JOB;
        }

        @Override
        public boolean supports(ContractStatusEvent event) {
            if (failOnSupports) {
                throw new IllegalStateException("후속 작업 등록 실패 주입");
            }
            return supported.contains(event.getNewStatus());
        }

        @Override
        public List<ContractStatusEffect> process(ContractStatusEvent event) {
            processed.add(event.getContractStatusEventId());
            if (failuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new IllegalStateException("후속 처리 실패 주입");
            }
            return List.of(new ContractStatusEffect(ContractStatusEffect.EntityType.SCHEDULE_LINE,
                    event.getContractStatusEventId() * 10, "PLANNED", "HOLD", new BigDecimal("1000"), null,
                    "TEST_HOLD", "테스트 보류"));
        }
    }
}
