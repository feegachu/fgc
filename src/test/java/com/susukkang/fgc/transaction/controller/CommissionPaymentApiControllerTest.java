package com.susukkang.fgc.transaction.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;
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
import java.time.YearMonth;

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

        mockMvc.perform(post("/api/commission-payments")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.paymentId").value(101))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void settlementRoleUpdatesDraftPayment() throws Exception {
        given(commissionPaymentService.update(eq(101L), any())).willReturn(response(
                CommissionPaymentStatus.DRAFT
        ));

        mockMvc.perform(put("/api/commission-payments/101")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateJson()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.paymentId").value(101));
    }

    @Test
    void settlementRoleConfirmsPayment() throws Exception {
        given(commissionPaymentService.confirm(101L)).willReturn(response(
                CommissionPaymentStatus.CONFIRMED
        ));

        mockMvc.perform(post("/api/commission-payments/101/confirm")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));
    }

    @Test
    void rejectsNonSettlementRole() throws Exception {
        mockMvc.perform(post("/api/commission-payments")
                        .with(user("ga-admin").roles("GA_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsUpdateAndConfirmForNonSettlementRole() throws Exception {
        mockMvc.perform(put("/api/commission-payments/101")
                        .with(user("ga-admin").roles("GA_ADMIN"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validUpdateJson()))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/commission-payments/101/confirm")
                        .with(user("ga-admin").roles("GA_ADMIN"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    void rejectsMissingRequiredValues() throws Exception {
        mockMvc.perform(post("/api/commission-payments")
                        .with(user("settlement01").roles("SETTLEMENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
    }

    private String validCreateJson() throws Exception {
        return objectMapper.writeValueAsString(new java.util.LinkedHashMap<>(java.util.Map.ofEntries(
                java.util.Map.entry("sourceBusinessKey", "GA-2026-07-0001"),
                java.util.Map.entry("paymentSequence", 1),
                java.util.Map.entry("contractId", 3L),
                java.util.Map.entry("agentId", 7L),
                java.util.Map.entry("commissionItemCode", "BASE_COMMISSION"),
                java.util.Map.entry("amount", 500000),
                java.util.Map.entry("attributionMonth", "2026-07"),
                java.util.Map.entry("scheduledPaymentDate", "2026-07-25"),
                java.util.Map.entry("paymentStage", "GA_TO_FC"),
                java.util.Map.entry("attributedContractId", 3L),
                java.util.Map.entry("inclusionDecisionStatus", "INCLUDED"),
                java.util.Map.entry("inclusionDecisionReason", "룰셋 산입"),
                java.util.Map.entry("allocationPolicyVersion", 3L),
                java.util.Map.entry("allocationBasis", "DIRECT"),
                java.util.Map.entry("evidenceRef", "EVIDENCE-001"),
                java.util.Map.entry("attributionMethod", "DIRECT")
        )));
    }

    private String validUpdateJson() throws Exception {
        java.util.Map<String, Object> values = objectMapper.readValue(
                validCreateJson(),
                new com.fasterxml.jackson.core.type.TypeReference<>() {
                }
        );
        values.remove("sourceBusinessKey");
        return objectMapper.writeValueAsString(values);
    }

    private CommissionPaymentResponse response(CommissionPaymentStatus status) {
        return new CommissionPaymentResponse(
                101L,
                "GA-2026-07-0001",
                1,
                3L,
                7L,
                "BASE_COMMISSION",
                "FC 기본수수료",
                new BigDecimal("500000"),
                YearMonth.of(2026, 7),
                LocalDate.of(2026, 7, 25),
                PaymentStage.GA_TO_FC,
                status,
                3L,
                InclusionDecisionStatus.INCLUDED,
                "룰셋 산입",
                3L,
                "DIRECT",
                "EVIDENCE-001",
                AttributionMethod.DIRECT,
                null,
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00"),
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00")
        );
    }
}
