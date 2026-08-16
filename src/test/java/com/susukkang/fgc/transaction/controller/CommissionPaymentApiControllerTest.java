package com.susukkang.fgc.transaction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
import com.susukkang.fgc.transaction.dto.CommissionPaymentAttributionResponse;
import com.susukkang.fgc.transaction.dto.TransactionPrecheckResponse;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : 수수료 지급 건 등록·수정·확정 API 테스트
 *
 * @author yslee
 * @since 2026-08-07
 * @version 1.2
 */
@WebMvcTest(CommissionPaymentApiController.class)
@Import({
        CommissionPaymentApiController.class,
        GlobalExceptionHandler.class,
        SecurityConfig.class
})
class CommissionPaymentApiControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private CommissionPaymentService commissionPaymentService;

    @MockitoBean
    private FgcMessageResolver messageResolver;

    @MockitoBean
    private ConstraintErrorCodeResolver constraintErrorCodeResolver;

    @Test
    void settlementRoleCreatesDraftPayment() throws Exception {
        given(commissionPaymentService.create(any())).willReturn(response(
                CommissionPaymentStatus.DRAFT
        ));

        mockMvc.perform(post("/api/v1/transactions")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.commissionTransactionId").value(101))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.settlementMonth").value("2026-07-01"))
                .andExpect(jsonPath("$.data.evidenceRef").value("PAYMENT-EVIDENCE"))
                .andExpect(jsonPath("$.data.attributions[0].attributionDate").value("2026-07-10"))
                .andExpect(jsonPath("$.data.attributions[0].attributionMonth").value("2026-07-01"));
    }

    // 2026-08-11 yslee - 귀속 입력 전 DRAFT 등록 API 허용 검증
    // 기존 코드: attributions에 @NotEmpty를 적용하여 빈 목록을 400으로 차단
    // 문제: TRAN-W02에서 지급 본문을 먼저 임시저장한 뒤 귀속행을 추가할 수 없음
    // 개선: 빈 목록 DRAFT는 등록하고 확정 요청에서만 귀속 누락을 차단
    @Test
    void createsDraftBeforeAnyAttributionIsEntered() throws Exception {
        given(commissionPaymentService.create(any())).willReturn(response(
                CommissionPaymentStatus.DRAFT
        ));
        java.util.Map<String, Object> values = objectMapper.readValue(
                validCreateJson(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                }
        );
        values.put("attributions", List.of());

        mockMvc.perform(post("/api/v1/transactions")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(values)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    // 2026-08-11 yslee - 지급 상태 변경 API의 CSRF 토큰 필수 검증
    // 기존 코드: 테스트가 csrf()를 항상 넣어 토큰 없는 위조 요청의 차단 여부를 확인하지 않음
    // 문제: SecurityConfig에서 CSRF가 다시 꺼져도 지급 등록 테스트가 계속 통과
    // 개선: 인증된 세션이어도 토큰이 없으면 403이고 서비스는 호출되지 않음을 검증
    @Test
    void rejectsCreateWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isForbidden());

        verify(commissionPaymentService, never()).create(any());
    }

    // 2026-08-11 yslee - 역할 매트릭스의 SYSTEM_ADMIN 전체권한 회귀 검증
    // 기존 코드: SETTLEMENT 역할 성공과 GA_ADMIN 거부만 테스트
    // 문제: SYSTEM_ADMIN이 모든 기능에 접근해야 한다는 공통 권한 계약 위반을 감지하지 못함
    // 개선: 시스템관리자가 동일 등록 API를 호출할 수 있는지 검증
    @Test
    void systemAdminCreatesDraftPayment() throws Exception {
        given(commissionPaymentService.create(any())).willReturn(response(
                CommissionPaymentStatus.DRAFT
        ));

        mockMvc.perform(post("/api/v1/transactions")
                        .with(user("system-admin").roles("SYSTEM_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isCreated());
    }

    @Test
    void settlementRoleUpdatesDraftPayment() throws Exception {
        given(commissionPaymentService.update(eq(101L), any())).willReturn(response(
                CommissionPaymentStatus.DRAFT
        ));

        mockMvc.perform(put("/api/v1/transactions/101")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commissionTransactionId").value(101))
                .andExpect(jsonPath("$.data.settlementMonth").value("2026-07-01"))
                .andExpect(jsonPath("$.data.attributions[0].attributionDate").value("2026-07-10"));
    }

    @Test
    void settlementRoleConfirmsPayment() throws Exception {
        given(commissionPaymentService.confirm(101L, "confirm-101")).willReturn(response(
                CommissionPaymentStatus.CONFIRMED
        ));

        mockMvc.perform(post("/api/v1/transactions/101/confirm")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .with(csrf())
                        .header("Idempotency-Key", "confirm-101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.capCheckIds[0]").value(55));
    }

    // IF-API-24 (FGC-FUN-033) — 사전검증 미리보기는 차단 사유가 있어도 200 + blockers[]로 응답한다
    @Test
    void settlementRolePrechecksPayment() throws Exception {
        given(commissionPaymentService.precheck(101L)).willReturn(precheckResponse());

        mockMvc.perform(post("/api/v1/transactions/101/precheck")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(101))
                .andExpect(jsonPath("$.data.confirmable").value(false))
                // SIR-008 — 금액은 JSON 숫자, 사용률은 문자열, 코드에는 한글 라벨 동봉
                .andExpect(jsonPath("$.data.capPreview[0].limitAmount").value(1200000))
                .andExpect(jsonPath("$.data.capPreview[0].usagePct").value("108.333333"))
                .andExpect(jsonPath("$.data.capPreview[0].paymentStageLabel")
                        .value(PaymentStage.GA_TO_FC.label()))
                // 저장하지 않으므로 capCheckId는 항상 null
                .andExpect(jsonPath("$.data.capPreview[0].capCheckId")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.data.blockers[0].code").value("FGC-CAP-001"));
    }

    @Test
    void rejectsPrecheckWithoutCsrfToken() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/101/precheck")
                        .with(user("settlement01").roles("SETTLEMENT")))
                .andExpect(status().isForbidden());

        verify(commissionPaymentService, never()).precheck(any());
    }

    // IF-API-24 완료 조건 — 비로그인은 401
    @Test
    void rejectsUnauthenticatedPrecheck() throws Exception {
        mockMvc.perform(post("/api/v1/transactions/101/precheck")
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        verify(commissionPaymentService, never()).precheck(any());
    }

    @Test
    void rejectsNonSettlementRole() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .with(user("ga-admin").roles("GA_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUpdateAndConfirmForNonSettlementRole() throws Exception {
        mockMvc.perform(put("/api/v1/transactions/101")
                        .with(user("ga-admin").roles("GA_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateJson()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/transactions/101/confirm")
                        .with(user("ga-admin").roles("GA_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/transactions/101/precheck")
                        .with(user("ga-admin").roles("GA_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    // FUN-002(#82) — COMPLIANCE는 §4-1 "조회만"이라 등록·수정·확정 전부 403이어야 한다.
    @Test
    void rejectsCreateUpdateAndConfirmForComplianceRole() throws Exception {
        // 필터 단계 거부도 ApiResponse 봉투로 나가야 한다(apiAccessDeniedHandler, 이슈 #82 인수조건)
        mockMvc.perform(post("/api/v1/transactions")
                        .with(user("comp01").roles("COMPLIANCE"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"))
                .andExpect(jsonPath("$.data").doesNotExist());

        mockMvc.perform(put("/api/v1/transactions/101")
                        .with(user("comp01").roles("COMPLIANCE"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateJson()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/transactions/101/confirm")
                        .with(user("comp01").roles("COMPLIANCE"))
                        .with(csrf()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/transactions/101/precheck")
                        .with(user("comp01").roles("COMPLIANCE"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMissingRequiredValues() throws Exception {
        mockMvc.perform(post("/api/v1/transactions")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
    }

    // 2026-08-11 yslee - 귀속 목록의 null 요소를 Bean Validation 단계에서 차단
    // 기존 코드: attributions 목록 자체만 검증하여 [null] 요청이 서비스 NPE로 이어짐
    // 문제: 잘못된 사용자 입력이 400이 아닌 500으로 응답될 수 있음
    // 개선: 목록 요소 @NotNull 계약과 API 회귀 테스트를 추가
    @Test
    void rejectsNullAttributionElement() throws Exception {
        java.util.Map<String, Object> values = objectMapper.readValue(
                validCreateJson(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                }
        );
        values.put("attributions", java.util.Collections.singletonList(null));

        mockMvc.perform(post("/api/v1/transactions")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(values)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
    }

    private String validCreateJson() throws Exception {
        return objectMapper.writeValueAsString(new java.util.LinkedHashMap<>(java.util.Map.ofEntries(
                java.util.Map.entry("sourceType", "GA_MANUAL_PAYMENT"),
                java.util.Map.entry("sourceBusinessKey", "GA-2026-07-0001"),
                java.util.Map.entry("contractId", 3L),
                java.util.Map.entry("agentId", 7L),
                java.util.Map.entry("commissionItemId", 11L),
                java.util.Map.entry("amount", 500000),
                java.util.Map.entry("settlementMonth", "2026-07-01"),
                java.util.Map.entry("cashflowType", "PAYMENT"),
                java.util.Map.entry("scheduledPaymentDate", "2026-07-25"),
                java.util.Map.entry("paymentStage", "GA_TO_FC"),
                java.util.Map.entry("allocationPolicyVersion", 3L),
                java.util.Map.entry("evidenceRef", "PAYMENT-EVIDENCE"),
                java.util.Map.entry("attributions", List.of(java.util.Map.of(
                        "contractId", 3L,
                        "attributionDate", "2026-07-10",
                        "amount", 500000,
                        "inclusionDecisionStatus", "INCLUDED",
                        "exclusionType", "NONE",
                        "inclusionDecisionReason", "룰셋 산입",
                        "allocationBasis", "DIRECT",
                        "evidenceRef", "EVIDENCE-001",
                        "attributionMethod", "DIRECT"
                )))
        )));
    }

    private String validUpdateJson() throws Exception {
        java.util.Map<String, Object> values = objectMapper.readValue(
                validCreateJson(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                }
        );
        return objectMapper.writeValueAsString(values);
    }

    private TransactionPrecheckResponse precheckResponse() {
        return new TransactionPrecheckResponse(
                101L,
                List.of(new TransactionPrecheckResponse.CapPreviewItem(
                        3L,
                        "CT-2026-0003",
                        PaymentStage.GA_TO_FC,
                        PaymentStage.GA_TO_FC.label(),
                        LocalDate.of(2026, 7, 3),
                        100000L,
                        0L,
                        0L,
                        1200000L,
                        0L,
                        1300000L,
                        1300000L,
                        -100000L,
                        "108.333333",
                        CapResultStatus.VIOLATION,
                        CapResultStatus.VIOLATION.label(),
                        31L,
                        null
                )),
                List.of(new TransactionPrecheckResponse.Blocker(
                        "FGC-CAP-001",
                        "지급 확정 불가 — 1,200% 한도 초과(사용률 108.333333%). 예외함에서 정정·감액·취소를 선택하세요.",
                        3L,
                        201L
                )),
                false
        );
    }

    private CommissionPaymentResponse response(CommissionPaymentStatus status) {
        return new CommissionPaymentResponse(
                101L,
                "GA_MANUAL_PAYMENT",
                "GA-2026-07-0001",
                3L,
                7L,
                11L,
                "BASE_COMMISSION",
                "FC 기본수수료",
                new BigDecimal("500000"),
                LocalDate.of(2026, 7, 1),
                "PAYMENT",
                LocalDate.of(2026, 7, 25),
                PaymentStage.GA_TO_FC,
                status,
                3L,
                List.of(new CommissionPaymentAttributionResponse(
                        1,
                        3L,
                        LocalDate.of(2026, 7, 10),
                        LocalDate.of(2026, 7, 1),
                        new BigDecimal("500000"),
                        InclusionDecisionStatus.INCLUDED,
                        ExclusionType.NONE,
                        "룰셋 산입",
                        "DIRECT",
                        "EVIDENCE-001",
                        AttributionMethod.DIRECT
                )),
                List.of(55L),
                null,
                "PAYMENT-EVIDENCE",
                null,
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00")
        );
    }
}
