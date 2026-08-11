package com.susukkang.fgc.transaction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.code.AttributionMethod;
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
                .andExpect(jsonPath("$.data.attributions[0].attributionDate").value("2026-07-10"))
                .andExpect(jsonPath("$.data.attributions[0].attributionMonth").value("2026-07-01"));
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
                java.util.Map.entry("paymentSequence", 1),
                java.util.Map.entry("contractId", 3L),
                java.util.Map.entry("agentId", 7L),
                java.util.Map.entry("commissionItemId", 11L),
                java.util.Map.entry("amount", 500000),
                java.util.Map.entry("settlementMonth", "2026-07-01"),
                java.util.Map.entry("cashflowType", "PAYMENT"),
                java.util.Map.entry("scheduledPaymentDate", "2026-07-25"),
                java.util.Map.entry("paymentStage", "GA_TO_FC"),
                java.util.Map.entry("allocationPolicyVersion", 3L),
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

    private CommissionPaymentResponse response(CommissionPaymentStatus status) {
        return new CommissionPaymentResponse(
                101L,
                "GA_MANUAL_PAYMENT",
                "GA-2026-07-0001",
                1,
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
                null,
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00")
        );
    }
}
