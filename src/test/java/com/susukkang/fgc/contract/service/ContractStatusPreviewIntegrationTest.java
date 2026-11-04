package com.susukkang.fgc.contract.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.auth.service.FgcUserDetailsService;
import com.susukkang.fgc.clawback.dto.ClawbackCalculationRequest;
import com.susukkang.fgc.clawback.service.ClawbackCalculationService;
import com.susukkang.fgc.clawback.service.ClawbackCandidateCaptureService;
import com.susukkang.fgc.clawback.service.ClawbackCandidateQueryService;
import com.susukkang.fgc.common.code.ContractStatus;
import com.susukkang.fgc.common.code.PaymentCycleCode;
import com.susukkang.fgc.common.util.DateUtil;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractStatusEventRequest;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.entity.InsuranceContract;
import com.susukkang.fgc.contract.repository.InsuranceContractRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static com.susukkang.fgc.common.code.ContractStatus.ACTIVE;
import static com.susukkang.fgc.common.code.ContractStatus.LAPSED;
import static com.susukkang.fgc.common.code.ContractStatus.REVIVED;
import static com.susukkang.fgc.common.code.ContractStatus.TERMINATED;
import static com.susukkang.fgc.common.code.ContractStatus.UNPAID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * FUN-077 / IF-API-64 인수 시험 `후순위077 인수조건`. 실제 PostgreSQL·HTTP 로 ① 미리보기 전후 DB 전체가 그대로이고
 * ② 같은 스냅샷에서 실제 IF-API-19 저장과 후속 처리기가 남긴 영향·회차·보류가 미리보기와 같으며 ③ 전이 불가·입력 오류·근거 부족과
 * null/0 을 구분하고 ④ 오래된 결과로 저장을 우회하지 못함을 확인한다. 요청이 커밋되므로 전용 DB 를 쓴다.
 * 환수 정책을 바꾸는 시험만 메서드 트랜잭션으로 되돌린다.
 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_status_preview?currentSchema=fgc")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Timeout(120)
class ContractStatusPreviewIntegrationTest {

    private static final String CONTRACT_FEE = "BASE_COMMISSION";
    private static final String NO_RULE_FEE = "MAINTENANCE_COMMISSION";

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private ContractService contracts;
    @Autowired private InsuranceContractRepository contractRepository;
    @Autowired private ContractStatusFollowUpService followUps;
    @Autowired private FgcUserDetailsService users;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ClawbackCandidateCaptureService capture;
    @Autowired private ClawbackCandidateQueryService candidateQuery;
    @Autowired private ClawbackCalculationService calculation;

    private FgcUserDetails settle;

    @BeforeEach
    void setUp() {
        settle = (FgcUserDetails) users.loadUserByUsername("settle01");
        followUps.runDueJobs();
    }

    @Test
    @DisplayName("후순위077 인수조건: 실효 미리보기는 DB 를 바꾸지 않고 실제 저장 후 결과와 같다(월잠금·확정 제외 포함)")
    void lapsePreviewChangesNothingAndMatchesTheSavedResult() throws Exception {
        Long id = contractWithOwnSchedule();
        long header = header(id);
        finalizeMonth("2026-08-01");
        long locked = line(header, 1, "2026-08-20", "PLANNED");
        long confirmed = line(header, 2, "2026-09-05", "CONFIRMED");
        long dueWhileUnpaid = line(header, 3, "2026-09-10", "PLANNED");
        long dueAfterLapse = line(header, 4, "2026-09-25", "PLANNED");
        long heldEarlier = payment(id, CONTRACT_FEE, "GA_TO_FC", "2026-08-15", "1000", "DRAFT");
        save(id, UNPAID, "2026-08-01T00:00:00+09:00");
        // 미납 처리가 끝난 뒤에 만든 초안은 실효 때 처음 보류된다.
        long draftAfterUnpaid = payment(id, CONTRACT_FEE, "GA_TO_FC", "2026-09-20", "3000", "DRAFT");
        long confirmedPayment = payment(id, CONTRACT_FEE, "GA_TO_FC", "2026-09-12", "5000", "CONFIRMED");
        Map<String, String> before = database();

        JsonNode preview = preview(id, LAPSED, "2026-09-15T00:00:00+09:00");

        assertThat(database()).as("미리보기 전후 모든 업무 표의 행 수와 내용").isEqualTo(before);
        assertThat(ids(preview, "stoppedLines")).containsExactlyInAnyOrder(dueWhileUnpaid, dueAfterLapse);
        assertThat(preview.path("stoppedLines")).allSatisfy(line -> {
            assertThat(line.path("previousState").asText()).isEqualTo("HOLD");
            assertThat(line.path("nextState").asText()).isEqualTo("CANCELLED");
            assertThat(line.path("reason").asText()).isNotBlank();
        });
        assertThat(ids(preview, "resumedLines")).isEmpty();
        assertThat(reasonOf(preview, "excludedLines", locked)).isEqualTo("MONTH_LOCKED");
        assertThat(reasonOf(preview, "excludedLines", confirmed)).isEqualTo("ALREADY_CONFIRMED");
        assertThat(paymentNext(preview, draftAfterUnpaid)).isEqualTo("HELD");
        assertThat(paymentNext(preview, confirmedPayment)).isEqualTo("EXCLUDED");
        assertThat(paymentNext(preview, heldEarlier)).as("이미 보류된 지급 건은 바뀌지 않는다").isNull();
        assertAmount(preview.path("heldAmount").path("before"), "1000");
        assertAmount(preview.path("heldAmount").path("after"), "4000");
        assertAmount(paidAmount(preview, "GA_TO_FC"), "5000");
        assertThat(preview.path("pendingFollowUps").asInt()).isZero();
        assertThat(OffsetDateTime.parse(preview.path("snapshotAt").asText())).isBeforeOrEqualTo(DateUtil.nowSeoul());
        assertThat(preview(id, LAPSED, "2026-09-15T00:00:00+09:00").path("stoppedLines"))
                .as("같은 스냅샷이면 다시 불러도 같다").isEqualTo(preview.path("stoppedLines"));

        long event = save(id, LAPSED, "2026-09-15T00:00:00+09:00");

        assertMatchesActual(preview, event, id);
        assertThat(lineStatus(locked)).isEqualTo("PLANNED");
        assertThat(lineStatus(confirmed)).isEqualTo("CONFIRMED");
    }

    @Test
    @DisplayName("후순위077 인수조건: 실제로 생성한 스케줄에서 해지 미리보기가 저장 후 결과와 같다")
    void terminationPreviewMatchesOnAGeneratedSchedule() throws Exception {
        Long id = newContract();
        Map<String, String> before = database();

        JsonNode preview = preview(id, TERMINATED, "2026-09-15T00:00:00+09:00");

        assertThat(database()).isEqualTo(before);
        assertThat(ids(preview, "stoppedLines")).as("생성된 회차 중 효력일 이후 회차가 멈춘다").isNotEmpty();
        assertThat(preview.path("stoppedLines").get(0).path("commissionItemName").asText()).isNotBlank();
        assertThat(preview.path("stoppedLines").get(0).path("paymentStage").asText()).isNotBlank();
        assertThat(preview.path("stoppedLines").get(0).path("dueDate").asText()).isNotBlank();
        assertMatchesActual(preview, save(id, TERMINATED, "2026-09-15T00:00:00+09:00"), id);
    }

    @Test
    @DisplayName("후순위077 인수조건: 미납 미리보기와 납입 재개 미리보기(지급예정일 재설정, 보류 해제)가 저장 후 결과와 같다")
    void unpaidAndResumePreviewsMatchTheSavedResults() throws Exception {
        Long id = contractWithOwnSchedule();
        long header = header(id);
        long beforeResume = line(header, 1, "2026-09-25", "PLANNED");
        long afterResume = line(header, 2, "2026-10-25", "PLANNED");
        long held = payment(id, CONTRACT_FEE, "GA_TO_FC", "2026-09-20", "2000", "DRAFT");

        JsonNode unpaid = preview(id, UNPAID, "2026-09-01T00:00:00+09:00");

        assertThat(ids(unpaid, "stoppedLines")).containsExactlyInAnyOrder(beforeResume, afterResume);
        assertThat(unpaid.path("stoppedLines").get(0).path("nextState").asText()).as("미납은 중단이 아니라 보류").isEqualTo("HOLD");
        assertAmount(unpaid.path("heldAmount").path("before"), "0");
        assertAmount(unpaid.path("heldAmount").path("after"), "2000");
        assertMatchesActual(unpaid, save(id, UNPAID, "2026-09-01T00:00:00+09:00"), id);

        Map<String, String> before = database();
        JsonNode resume = preview(id, ACTIVE, "2026-10-05T00:00:00+09:00");

        assertThat(database()).isEqualTo(before);
        assertThat(ids(resume, "resumedLines")).containsExactlyInAnyOrder(beforeResume, afterResume);
        assertThat(line(resume, "resumedLines", beforeResume).path("nextDueDate").asText()).isEqualTo("2026-10-05");
        assertThat(line(resume, "resumedLines", afterResume).path("nextDueDate").isNull()).isTrue();
        assertThat(paymentNext(resume, held)).isEqualTo("RELEASED");
        assertAmount(resume.path("heldAmount").path("after"), "0");
        assertThat(resume.path("clawbackReview").path("candidate").asBoolean()).as("납입 재개는 환수 후보가 아니다").isFalse();
        assertMatchesActual(resume, save(id, ACTIVE, "2026-10-05T00:00:00+09:00"), id);
    }

    @Test
    @DisplayName("후순위077 인수조건: 부활 미리보기는 실효 기간을 소급하지 않고(NO_RETROACTIVE) 저장 후 결과와 같다")
    void revivalPreviewDoesNotBackfillAndMatchesTheSavedResult() throws Exception {
        Long id = contractWithOwnSchedule();
        long header = header(id);
        long lapsePeriod = line(header, 1, "2026-09-15", "PLANNED");
        long afterRevival = line(header, 2, "2026-10-15", "PLANNED");
        long heldInLapse = payment(id, CONTRACT_FEE, "GA_TO_FC", "2026-09-20", "700", "DRAFT");
        long heldAfterRevival = payment(id, CONTRACT_FEE, "GA_TO_FC", "2026-10-20", "300", "DRAFT");
        save(id, UNPAID, "2026-08-10T00:00:00+09:00");
        save(id, LAPSED, "2026-09-01T00:00:00+09:00");
        Map<String, String> before = database();

        JsonNode preview = preview(id, REVIVED, "2026-10-01T00:00:00+09:00");

        assertThat(database()).isEqualTo(before);
        assertThat(ids(preview, "resumedLines")).containsExactly(afterRevival);
        assertThat(ids(preview, "stoppedLines")).isEmpty();
        assertThat(reasonOf(preview, "excludedLines", lapsePeriod)).isEqualTo("NO_RETROACTIVE");
        assertThat(paymentNext(preview, heldAfterRevival)).isEqualTo("RELEASED");
        assertThat(paymentNext(preview, heldInLapse)).isEqualTo("EXCLUDED");
        assertAmount(preview.path("heldAmount").path("before"), "1000");
        assertAmount(preview.path("heldAmount").path("after"), "700");
        assertMatchesActual(preview, save(id, REVIVED, "2026-10-01T00:00:00+09:00"), id);
        assertThat(lineStatus(lapsePeriod)).isEqualTo("CANCELLED");
    }

    @Test
    @DisplayName("다른 사유의 보류가 남는 지급 건은 해제 판단이 나도 변경 후 보류 금액에 남고 실제 결과와 같다")
    void aPaymentWithAnotherOpenHoldStaysHeldAfterPartialRelease() throws Exception {
        Long id = contractWithOwnSchedule();
        long payment = payment(id, CONTRACT_FEE, "GA_TO_FC", "2026-10-20", "900", "DRAFT");
        save(id, UNPAID, "2026-09-01T00:00:00+09:00");
        jdbc.update("""
                INSERT INTO fgc.payment_hold(commission_transaction_id, contract_status_event_id, reason_code)
                SELECT ?, contract_status_event_id, 'STATUS_INACTIVE' FROM fgc.contract_status_event
                 WHERE contract_id = ? AND event_seq = 1
                """, payment, id);

        JsonNode preview = preview(id, ACTIVE, "2026-10-05T00:00:00+09:00");

        assertThat(paymentNext(preview, payment)).isEqualTo("RELEASED");
        assertAmount(preview.path("heldAmount").path("after"), "900");
        assertMatchesActual(preview, save(id, ACTIVE, "2026-10-05T00:00:00+09:00"), id);
    }

    @Test
    @DisplayName("끝나지 않은 앞 사건의 후속 작업이 있으면 결과와 함께 건수를 알린다")
    void pendingEarlierFollowUpsAreReported() throws Exception {
        Long id = contractWithOwnSchedule();
        saveWithoutFollowUp(id, UNPAID, "2026-09-01T00:00:00+09:00");

        JsonNode preview = preview(id, LAPSED, "2026-09-15T00:00:00+09:00");

        assertThat(preview.path("pendingFollowUps").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("입력 오류 400, 없는 ID 404, 전이 불가 409, 일자 범위와 근거 부족 422 를 서로 구분하고 아무것도 바꾸지 않는다")
    void distinguishesInvalidInputMissingIdDeniedTransitionAndMissingEvidence() throws Exception {
        Long id = newContract();
        Map<String, String> before = database();

        request(id, "{\"effectiveAt\":\"2026-09-15T00:00:00+09:00\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"))
                .andExpect(jsonPath("$.error.field").value("eventType"));
        request(id, "{\"eventType\":\"LAPSED\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("effectiveAt"));
        request(id, "{\"eventType\":\"NOT_A_STATUS\",\"effectiveAt\":\"2026-09-15T00:00:00+09:00\"}")
                .andExpect(status().isBadRequest());
        request(id, "{\"eventType\":\"UNPAID\",\"effectiveAt\":\"yesterday\"}")
                .andExpect(status().isBadRequest());
        request(Long.MAX_VALUE, body(UNPAID, "2026-09-15T00:00:00+09:00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-004"));
        // 표에 없는 전이와 같은 상태는 저장(IF-API-19)과 같은 코드로 거절한다(운영정책서 제13조의2 1항).
        request(id, body(LAPSED, "2026-09-15T00:00:00+09:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-TRANSITION_DENIED"))
                .andExpect(jsonPath("$.error.field").value("eventType"));
        request(id, body(ACTIVE, "2026-09-15T00:00:00+09:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-TRANSITION_DENIED"));
        // 일자 범위(같은 조 3항): 최신 사건 이전과 미래는 저장에서도 거절된다.
        request(id, body(UNPAID, "2020-01-01T00:00:00+09:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-EVENT_ORDER"))
                .andExpect(jsonPath("$.error.field").value("effectiveAt"));
        request(id, body(UNPAID, DateUtil.nowSeoul().plusDays(1).toString()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-EVENT_ORDER"))
                .andExpect(jsonPath("$.error.field").value("effectiveAt"));
        // 상태별 지급조건 정책이 없는 계약일이면 판단하지 않는다. 0건으로 바꾸지 않는다.
        jdbc.update("UPDATE fgc.insurance_contract SET contract_date = DATE '2025-06-01' WHERE contract_id = ?", id);
        before = database();
        request(id, body(UNPAID, "2026-09-15T00:00:00+09:00"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-POLICY_MISSING"))
                .andExpect(jsonPath("$.data").doesNotExist());

        assertThat(database()).isEqualTo(before);
    }

    @Test
    @DisplayName("SETTLEMENT·GA_ADMIN·SYSTEM_ADMIN 만 호출하고 COMPLIANCE·AGENT 는 403, 미인증은 401 이다")
    void onlyTheRolesInTheInterfaceTableMayPreview() throws Exception {
        Long id = newContract();
        String body = body(UNPAID, "2026-09-15T00:00:00+09:00");

        for (String role : List.of("SETTLEMENT", "GA_ADMIN", "SYSTEM_ADMIN")) {
            request(id, body, user("preview-" + role).roles(role)).andExpect(status().isOk());
        }
        for (String role : List.of("COMPLIANCE", "AGENT")) {
            request(id, body, user("preview-" + role).roles(role)).andExpect(status().isForbidden());
            // 없는 ID 로 존재 여부를 알아낼 수 없다.
            request(Long.MAX_VALUE, body, user("preview-" + role).roles(role)).andExpect(status().isForbidden());
        }
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/contracts/{id}/status-events/preview", id).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("미리보기 결과로 저장을 우회하지 못한다: 저장은 IF-API-19 가 최신 상태로 다시 검증한다")
    void aStalePreviewCannotBypassTheSave() throws Exception {
        Long id = newContract();
        ContractUpdateRequest staleSave = update(id, UNPAID, event("2026-09-01T00:00:00+09:00"));
        assertThat(ids(preview(id, UNPAID, "2026-09-01T00:00:00+09:00"), "stoppedLines")).isNotEmpty();

        save(id, TERMINATED, "2026-08-20T00:00:00+09:00");

        // 미리보기 뒤 다른 요청이 계약을 바꿨다: 옛 수정 시각은 409 이고 사건이 늘지 않는다.
        int events = count("SELECT count(*) FROM fgc.contract_status_event WHERE contract_id = ?", id);
        put(id, staleSave, UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-CONT-003"));
        // 최신 수정 시각을 써도 끝 상태라 전이가 막히고, 미리보기도 같은 이유로 거절한다.
        put(id, update(id, UNPAID, event("2026-09-01T00:00:00+09:00")), UUID.randomUUID().toString())
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-TRANSITION_DENIED"));
        request(id, body(UNPAID, "2026-09-01T00:00:00+09:00"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("FGC-IMPL-TRANSITION_DENIED"));
        assertThat(count("SELECT count(*) FROM fgc.contract_status_event WHERE contract_id = ?", id)).isEqualTo(events);
        assertThat(jdbc.queryForObject("SELECT current_status FROM fgc.insurance_contract WHERE contract_id = ?",
                String.class, id)).isEqualTo("TERMINATED");
    }

    @Test
    @Transactional
    @DisplayName("환수 정책 하나면 예상액이 실제 저장 후 환수 계산(IF-API-85)과 같고 규칙이 없는 항목은 금액에 넣지 않는다")
    void clawbackEstimateMatchesTheRealCalculationAfterSaving() throws Exception {
        Long id = contractWithOwnSchedule();
        payment(id, CONTRACT_FEE, "GA_TO_FC", "2026-08-01", "100.4", "CONFIRMED");
        payment(id, NO_RULE_FEE, "GA_TO_FC", "2026-08-01", "50", "CONFIRMED");
        payment(id, CONTRACT_FEE, "GA_TO_FC", "2026-08-01", "999", "DRAFT");
        payment(id, CONTRACT_FEE, "INSURER_TO_GA", "2026-08-01", "7000", "CONFIRMED");
        Map<String, String> before = database();

        JsonNode preview = preview(id, TERMINATED, "2026-09-15T00:00:00+09:00");

        assertThat(database()).isEqualTo(before);
        JsonNode review = preview.path("clawbackReview");
        assertThat(review.path("candidate").asBoolean()).isTrue();
        assertAmount(review.path("amount"), "100");
        assertThat(review.path("reason").asText()).contains("예상액").contains("확정액이 아니");
        assertThat(review.path("elapsedMonths").asInt()).as("계약일 2026-07-10 부터 2026-09-15 까지 채운 개월").isEqualTo(2);
        assertThat(review.path("policyCode").asText()).isEqualTo("CLAW-GA-VIRTUAL-2026");
        assertThat(review.path("lines")).hasSize(1);
        assertThat(review.path("lines").get(0).path("commissionItemName").asText()).isNotBlank();
        assertAmount(paidAmount(preview, "GA_TO_FC"), "150");
        assertAmount(paidAmount(preview, "INSURER_TO_GA"), "7000");

        long event = jdbc.queryForObject("SELECT contract_status_event_id FROM fgc.contract_status_event WHERE contract_id = ? AND event_seq = ?",
                Long.class, id, savedEvent(id, TERMINATED, "2026-09-15T00:00:00+09:00"));
        capture.captureStatusEvents(List.of(event));
        long candidate = jdbc.queryForObject("SELECT candidate_id FROM fgc.clawback_candidate WHERE status_event_id = ?", Long.class, event);
        var detail = candidateQuery.detail(candidate);
        var real = calculation.calculate(candidate, new ClawbackCalculationRequest(detail.candidate().sourceVersion(),
                review.path("policyVersionId").asLong(), "미리보기와 대조"), UUID.randomUUID().toString(), settle.getUserId());

        assertThat(real.amount()).isEqualByComparingTo(review.path("amount").decimalValue());
        assertThat(real.lines()).hasSize(1);
        assertThat(real.lines().get(0).transactionAttributionId())
                .isEqualTo(review.path("lines").get(0).path("transactionAttributionId").asLong());
        assertThat(detail.candidate().paidAmount()).isEqualByComparingTo(paidAmount(preview, "GA_TO_FC").decimalValue());
    }

    @Test
    @Transactional
    @DisplayName("환수 정책이 없으면 금액 null 과 '근거 없음'이고 0 으로 바꾸지 않는다(회사 약정 필요)")
    void noClawbackPolicyMeansNullAndNoBasis() throws Exception {
        Long id = contractWithOwnSchedule();
        payment(id, CONTRACT_FEE, "GA_TO_FC", "2026-08-01", "1000", "CONFIRMED");
        retireDemoClawbackPolicy();

        JsonNode review = preview(id, TERMINATED, "2026-09-15T00:00:00+09:00").path("clawbackReview");

        assertThat(review.path("candidate").asBoolean()).isTrue();
        assertThat(review.path("amount").isNull()).isTrue();
        assertThat(review.path("reason").asText()).startsWith("근거 없음");
        assertThat(review.path("reasonCodes").toString()).contains("POLICY_MISSING");
        assertThat(review.path("lines")).isEmpty();
    }

    @Test
    @Transactional
    @DisplayName("규칙이 0% 를 명시한 구간은 실제 0원이고, 구간 밖·정책 둘·기지급 없음·귀속 범위 불일치는 null 과 사유다")
    void clawbackDistinguishesRealZeroFromUncalculated() throws Exception {
        retireDemoClawbackPolicy();
        long zero = policy(CONTRACT_FEE, 0, 1199, "0");
        Long zeroContract = contractWithOwnSchedule();
        payment(zeroContract, CONTRACT_FEE, "GA_TO_FC", "2026-08-01", "1000", "CONFIRMED");

        JsonNode zeroReview = preview(zeroContract, TERMINATED, "2026-09-15T00:00:00+09:00").path("clawbackReview");

        assertThat(zeroReview.path("amount").isNull()).isFalse();
        assertAmount(zeroReview.path("amount"), "0");
        assertThat(zeroReview.path("policyVersionId").asLong()).isEqualTo(zero);

        // 기지급이 없으면 환수 근거가 없어 계산하지 않는다. 0원이 아니다.
        Long noPaid = contractWithOwnSchedule();
        JsonNode noPaidPreview = preview(noPaid, TERMINATED, "2026-09-15T00:00:00+09:00");
        assertThat(noPaidPreview.path("clawbackReview").path("amount").isNull()).isTrue();
        assertThat(noPaidPreview.path("clawbackReview").path("reasonCodes").toString()).contains("PAID_ATTRIBUTION_MISSING");
        assertAmount(paidAmount(noPaidPreview, "GA_TO_FC"), "0");

        // 귀속 범위가 어긋난 기지급은 자료를 믿을 수 없어 기지급도 환수도 null 이다.
        Long mismatch = contractWithOwnSchedule();
        long tx = payment(mismatch, CONTRACT_FEE, "GA_TO_FC", "2026-08-01", "1000", "DRAFT");
        jdbc.update("UPDATE fgc.transaction_attribution SET agent_id = NULL WHERE commission_transaction_id = ?", tx);
        jdbc.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?", tx);
        JsonNode mismatchPreview = preview(mismatch, TERMINATED, "2026-09-15T00:00:00+09:00");
        assertThat(paidAmount(mismatchPreview, "GA_TO_FC").isNull()).isTrue();
        assertThat(mismatchPreview.path("clawbackReview").path("amount").isNull()).isTrue();
        assertThat(mismatchPreview.path("clawbackReview").path("reasonCodes").toString()).contains("PAYMENT_SCOPE_MISMATCH");

        // 정책을 사람이 골라야 하면(제48조의3 4항) 임의로 고르지 않는다.
        policy(CONTRACT_FEE, 0, 1199, "50");
        JsonNode selection = preview(zeroContract, TERMINATED, "2026-09-15T00:00:00+09:00").path("clawbackReview");
        assertThat(selection.path("amount").isNull()).isTrue();
        assertThat(selection.path("reasonCodes").toString()).contains("POLICY_SELECTION_REQUIRED");
    }

    @Test
    @Transactional
    @DisplayName("경과월 구간이 비면 계산하지 않고, 미납처럼 후보가 아닌 사건은 환수 검토 대상이 아니라고 답한다")
    void uncoveredBandAndNonCandidateEventsAreNotZero() throws Exception {
        retireDemoClawbackPolicy();
        policy(CONTRACT_FEE, 100, 200, "100");
        Long id = contractWithOwnSchedule();
        payment(id, CONTRACT_FEE, "GA_TO_FC", "2026-08-01", "1000", "CONFIRMED");

        JsonNode uncovered = preview(id, TERMINATED, "2026-09-15T00:00:00+09:00").path("clawbackReview");
        assertThat(uncovered.path("amount").isNull()).isTrue();
        assertThat(uncovered.path("reasonCodes").toString()).contains("ELAPSED_MONTH_NOT_COVERED");

        JsonNode notCandidate = preview(id, UNPAID, "2026-09-15T00:00:00+09:00").path("clawbackReview");
        assertThat(notCandidate.path("candidate").asBoolean()).isFalse();
        assertThat(notCandidate.path("amount").isNull()).isTrue();
        assertThat(notCandidate.path("reason").asText()).contains("환수 검토 후보가 되지 않습니다");
    }

    // --- 대조 ---

    /** 실제 후속 처리가 남긴 영향 행과 회차·보류를 미리보기와 하나씩 맞춘다. */
    private void assertMatchesActual(JsonNode preview, long eventId, Long contractId) {
        assertThat(jdbc.queryForList("SELECT state FROM fgc.contract_event_job WHERE contract_status_event_id = ?",
                String.class, eventId)).as("실제 후속 처리가 끝났다").containsOnly("SUCCEEDED");
        List<String> expected = new ArrayList<>();
        for (String list : List.of("stoppedLines", "resumedLines", "excludedLines")) {
            preview.path(list).forEach(line -> expected.add(effect("SCHEDULE_LINE", line.path("scheduleLineId").asLong(),
                    line.path("previousState").asText(), line.path("nextState").asText(), line.path("amount").decimalValue(),
                    line.path("reasonCode").asText(), line.path("reason").asText())));
        }
        preview.path("payments").forEach(payment -> expected.add(effect("PAYMENT", payment.path("commissionTransactionId").asLong(),
                payment.path("previousState").asText(), payment.path("nextState").asText(), payment.path("amount").decimalValue(),
                payment.path("reasonCode").asText(), payment.path("reason").asText())));
        List<String> actual = jdbc.query("""
                SELECT entity_type, entity_id, previous_state, next_state, amount, reason_code, reason
                  FROM fgc.contract_status_event_effect WHERE contract_status_event_id = ?
                """, (rs, row) -> effect(rs.getString(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                rs.getBigDecimal(5), rs.getString(6), rs.getString(7)), eventId);
        assertThat(actual).as("실제 영향 행").containsExactlyInAnyOrderElementsOf(expected);

        for (String list : List.of("stoppedLines", "resumedLines")) {
            preview.path(list).forEach(line -> {
                long lineId = line.path("scheduleLineId").asLong();
                assertThat(lineStatus(lineId)).as("회차 %d 상태", lineId).isEqualTo(line.path("nextState").asText());
                String due = line.path("nextDueDate").isNull() ? line.path("dueDate").asText() : line.path("nextDueDate").asText();
                assertThat(jdbc.queryForObject("SELECT due_date::text FROM fgc.schedule_line WHERE schedule_line_id = ?",
                        String.class, lineId)).as("회차 %d 지급예정일", lineId).isEqualTo(due);
            });
        }
        assertThat(jdbc.queryForObject("""
                SELECT COALESCE(SUM(ta.attributed_amount), 0)
                  FROM fgc.transaction_attribution ta
                  JOIN fgc.commission_transaction ct ON ct.commission_transaction_id = ta.commission_transaction_id
                 WHERE ta.contract_id = ? AND ta.attribution_scope = 'CONTRACT' AND ct.status = 'DRAFT'
                   AND ct.cashflow_type = 'PAYMENT'
                   AND EXISTS (SELECT 1 FROM fgc.payment_hold h
                                 JOIN fgc.contract_status_event e ON e.contract_status_event_id = h.contract_status_event_id
                                WHERE h.commission_transaction_id = ct.commission_transaction_id
                                  AND h.released_at IS NULL AND e.contract_id = ?)
                """, BigDecimal.class, contractId, contractId))
                .as("실제 미해제 보류 금액").isEqualByComparingTo(preview.path("heldAmount").path("after").decimalValue());
    }

    private static String effect(String type, long id, String previous, String next, BigDecimal amount, String code, String reason) {
        return String.join("|", type, String.valueOf(id), previous, next, amount.stripTrailingZeros().toPlainString(), code, reason);
    }

    /** fgc 스키마의 모든 표. 행 수와 내용이 하나라도 바뀌면 다른 값이 된다. */
    private Map<String, String> database() {
        Map<String, String> tables = new TreeMap<>();
        for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname = 'fgc'", String.class)) {
            tables.put(table, jdbc.queryForObject("SELECT count(*) || ':' || COALESCE(md5(string_agg(t::text, '|' ORDER BY t::text)), '') "
                    + "FROM fgc.\"" + table + "\" t", String.class));
        }
        return tables;
    }

    private static List<Long> ids(JsonNode preview, String list) {
        List<Long> ids = new ArrayList<>();
        preview.path(list).forEach(line -> ids.add(line.path("scheduleLineId").asLong()));
        return ids;
    }

    private static JsonNode line(JsonNode preview, String list, long lineId) {
        for (JsonNode line : preview.path(list)) {
            if (line.path("scheduleLineId").asLong() == lineId) {
                return line;
            }
        }
        throw new AssertionError(list + " 에 회차 " + lineId + " 가 없다: " + preview.path(list));
    }

    private static String reasonOf(JsonNode preview, String list, long lineId) {
        return line(preview, list, lineId).path("reasonCode").asText();
    }

    private static String paymentNext(JsonNode preview, long paymentId) {
        for (JsonNode payment : preview.path("payments")) {
            if (payment.path("commissionTransactionId").asLong() == paymentId) {
                return payment.path("nextState").asText();
            }
        }
        return null;
    }

    private static JsonNode paidAmount(JsonNode preview, String stage) {
        for (JsonNode paid : preview.path("paidAmount")) {
            if (stage.equals(paid.path("paymentStage").asText())) {
                return paid.path("amount");
            }
        }
        throw new AssertionError(stage + " 기지급 행이 없다: " + preview.path("paidAmount"));
    }

    private static void assertAmount(JsonNode amount, String expected) {
        assertThat(amount.isNumber()).as("금액이 숫자로 온다: " + amount).isTrue();
        assertThat(amount.decimalValue()).isEqualByComparingTo(expected);
    }

    // --- API ---

    private JsonNode preview(Long id, ContractStatus type, String effectiveAt) throws Exception {
        ResultActions result = request(id, body(type, effectiveAt)).andExpect(status().isOk());
        return objectMapper.readTree(result.andReturn().getResponse().getContentAsString()).path("data");
    }

    private static String body(ContractStatus type, String effectiveAt) {
        return "{\"eventType\":\"" + type + "\",\"effectiveAt\":\"" + effectiveAt + "\"}";
    }

    private ResultActions request(Long id, String body) throws Exception {
        return request(id, body, user(settle));
    }

    private ResultActions request(Long id, String body, RequestPostProcessor as) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.post("/api/v1/contracts/{id}/status-events/preview", id)
                .with(as).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** 실제 IF-API-19 저장과 후속 처리까지 실행하고 사건 ID 를 돌려준다. */
    private long save(Long id, ContractStatus status, String effectiveAt) throws Exception {
        long eventId = saveWithoutFollowUp(id, status, effectiveAt);
        followUps.runDueJobs();
        return eventId;
    }

    private long saveWithoutFollowUp(Long id, ContractStatus status, String effectiveAt) throws Exception {
        ResultActions saved = put(id, update(id, status, event(effectiveAt)), UUID.randomUUID().toString()).andExpect(status().isOk());
        return objectMapper.readTree(saved.andReturn().getResponse().getContentAsString()).path("data").path("statusEventId").asLong();
    }

    /** 시험 트랜잭션 안의 저장(후속 작업은 별도 트랜잭션이라 돌리지 않는다). 사건 순번을 돌려준다. */
    private int savedEvent(Long id, ContractStatus status, String effectiveAt) throws Exception {
        saveWithoutFollowUp(id, status, effectiveAt);
        return count("SELECT max(event_seq) FROM fgc.contract_status_event WHERE contract_id = ?", id);
    }

    private ResultActions put(Long id, ContractUpdateRequest body, String key) throws Exception {
        return mvc.perform(MockMvcRequestBuilders.put("/api/v1/contracts/{id}", id)
                .with(user(settle)).with(csrf()).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ContractUpdateRequest update(Long id, ContractStatus status, ContractStatusEventRequest event) {
        InsuranceContract current = contractRepository.findById(id).orElseThrow();
        // 메서드 트랜잭션 안에서는 엔티티가 캐시되므로 수정 시각은 DB 에서 읽는다.
        OffsetDateTime updatedAt = jdbc.queryForObject("SELECT updated_at FROM fgc.insurance_contract WHERE contract_id = ?",
                OffsetDateTime.class, id);
        return new ContractUpdateRequest(current.getContractNo(), current.getInsurerId(),
                current.getProductOfferingId(), current.getContractDate(), status, current.getAgentId(),
                current.getOrganizationId(), current.getPaymentCycleCode(), current.getPremiumPerCycleAmount(),
                current.getFirstPremiumAmount(), current.getMonthlyEquivalentFirstPremium(),
                current.getPaymentTermMonths(), current.getStandardSurrenderDeductionAmount(), updatedAt, event);
    }

    private static ContractStatusEventRequest event(String effectiveAt) {
        return new ContractStatusEventRequest(OffsetDateTime.parse(effectiveAt), DateUtil.nowSeoul().minusMinutes(1),
                "MANUAL_STATUS_CHANGE", "보험회사 통보 반영");
    }

    // --- 데이터 ---

    private Long newContract() {
        InsuranceContract seed = contractRepository.findById(jdbc.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = 'FGC-FGL01-202607-0001'",
                Long.class)).orElseThrow();
        return contracts.createContract(new ContractCreateRequest(seed.getInsurerId(), "P077-" + UUID.randomUUID(),
                seed.getProductOfferingId(), seed.getContractDate(), ACTIVE, seed.getAgentId(), seed.getOrganizationId(),
                PaymentCycleCode.MONTHLY, new BigDecimal("100000"), new BigDecimal("100000"), new BigDecimal("100000"),
                120, BigDecimal.ZERO)).contractId();
    }

    /** 생성된 스케줄을 끄고 시나리오의 회차만 남긴다. */
    private Long contractWithOwnSchedule() {
        Long id = newContract();
        jdbc.update("UPDATE fgc.schedule_header SET active_yn = false WHERE contract_id = ?", id);
        return id;
    }

    private long header(Long contractId) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_header(contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, schedule_regime)
                SELECT ?, 'GA_TO_FC', (SELECT min(policy_version_id) FROM fgc.policy_version),
                       COALESCE(max(schedule_version_no), 0) + 1, 'OPERATIONAL', 'CURRENT'
                  FROM fgc.schedule_header WHERE contract_id = ?
                RETURNING schedule_header_id
                """, Long.class, contractId, contractId);
    }

    private long line(long headerId, int installment, String dueDate, String status) {
        return jdbc.queryForObject("""
                INSERT INTO fgc.schedule_line(schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, basis_code, basis_amount, calculation_type, fixed_amount, expected_amount, line_status)
                VALUES (?, ?, ?, ?, ?::date, (SELECT min(commission_item_id) FROM fgc.commission_item),
                        'TEST', 1000, 'FIXED', 1000, 1000, ?)
                RETURNING schedule_line_id
                """, Long.class, headerId, installment, installment, installment, dueDate, status);
    }

    private String lineStatus(long lineId) {
        return jdbc.queryForObject("SELECT line_status FROM fgc.schedule_line WHERE schedule_line_id = ?", String.class, lineId);
    }

    private long payment(Long contractId, String itemCode, String stage, String attributionDate, String amount, String status) {
        boolean toAgent = "GA_TO_FC".equals(stage);
        Long tx = jdbc.queryForObject("""
                INSERT INTO fgc.commission_transaction(payment_stage, source_type, source_business_key, insurer_id,
                    recipient_agent_id, commission_item_id, settlement_month, paid_on, amount, cashflow_type, installment_no)
                SELECT ?, ?, ?, c.insurer_id, CASE WHEN ? THEN c.agent_id END,
                       (SELECT commission_item_id FROM fgc.commission_item WHERE item_code = ?),
                       date_trunc('month', ?::date)::date, ?::date, ?, 'PAYMENT', 1
                  FROM fgc.insurance_contract c WHERE c.contract_id = ?
                RETURNING commission_transaction_id
                """, Long.class, stage, toAgent ? "GA_MANUAL_PAYMENT" : "INSURER_STATEMENT", UUID.randomUUID().toString(),
                toAgent, itemCode, attributionDate, attributionDate, new BigDecimal(amount), contractId);
        jdbc.update("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id, contract_id, agent_id, attribution_date,
                    attribution_month, attributed_amount, inclusion_status_snapshot, attribution_method)
                SELECT ?, c.contract_id, CASE WHEN ? THEN c.agent_id END, ?::date, date_trunc('month', ?::date)::date, ?, 'INCLUDED', 'DIRECT'
                  FROM fgc.insurance_contract c WHERE c.contract_id = ?
                """, tx, toAgent, attributionDate, attributionDate, new BigDecimal(amount), contractId);
        if (!"DRAFT".equals(status)) {
            jdbc.update("UPDATE fgc.commission_transaction SET status = ? WHERE commission_transaction_id = ?", status, tx);
        }
        return tx;
    }

    /** V7 생명주기 순서대로 그 달을 확정한다(월잠금). 이 달의 회차는 이 클래스에서 월잠금 시나리오만 쓴다. */
    private void finalizeMonth(String month) {
        Long runId = jdbc.queryForObject("""
                INSERT INTO fgc.validation_run(validation_month, run_no, run_type)
                SELECT ?::date, COALESCE(MAX(run_no), 0) + 1, 'PRE_CONFIRM' FROM fgc.validation_run
                 WHERE validation_month = ?::date
                RETURNING validation_run_id
                """, Long.class, month, month);
        jdbc.update("UPDATE fgc.validation_run SET status = 'RUNNING', current_step = 1, started_at = now() WHERE validation_run_id = ?", runId);
        jdbc.update("UPDATE fgc.validation_run SET status = 'COMPLETED', current_step = 8, completed_at = now() WHERE validation_run_id = ?", runId);
        jdbc.update("UPDATE fgc.validation_run SET status = 'FINALIZED', current_step = 10, finalized_at = now() WHERE validation_run_id = ?", runId);
    }

    private void retireDemoClawbackPolicy() {
        jdbc.update("UPDATE fgc.policy_version SET status = 'RETIRED' WHERE policy_code = 'CLAW-GA-VIRTUAL-2026' AND status = 'ACTIVE'");
    }

    /** 보험회사·판매버전을 비운 ACTIVE 환수 정책. 승인자와 작성자가 달라야 한다. */
    private long policy(String itemCode, int monthFrom, int monthTo, String ratePct) {
        long id = jdbc.queryForObject("""
                INSERT INTO fgc.policy_version(policy_code, policy_name, policy_type, source_class, version_no,
                    effective_from, status, created_by)
                VALUES (?, '미리보기 검증 환수 정책', 'CLAWBACK', 'GA_POLICY', 1, DATE '2020-01-01', 'DRAFT',
                        (SELECT min(user_id) FROM fgc.app_user))
                RETURNING policy_version_id
                """, Long.class, "P077-" + UUID.randomUUID());
        jdbc.update("""
                INSERT INTO fgc.clawback_rule(policy_version_id, commission_item_id, elapsed_month_from, elapsed_month_to,
                    clawback_basis_code, clawback_rate_pct, condition_expression)
                VALUES (?, (SELECT commission_item_id FROM fgc.commission_item WHERE item_code = ?), ?, ?,
                        'PAID_ATTRIBUTED_AMOUNT', ?, '{}'::jsonb)
                """, id, itemCode, monthFrom, monthTo, new BigDecimal(ratePct));
        jdbc.update("""
                UPDATE fgc.policy_version SET status = 'APPROVED', approved_at = now(),
                    approved_by = (SELECT max(user_id) FROM fgc.app_user) WHERE policy_version_id = ?
                """, id);
        jdbc.update("UPDATE fgc.policy_version SET status = 'ACTIVE' WHERE policy_version_id = ?", id);
        return id;
    }

    private int count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Integer.class, args);
    }
}
