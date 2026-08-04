package com.susukkang.fgc.cap.controller;

import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CapCheckController API 통합테스트(#3). CapCheckService 는 mock 으로 대체해 컨트롤러의
 * 요청·응답 변환, 인증, 예외 매핑만 검증한다 — 계산식 자체는 CapCalculatorImplTest 가 담당한다.
 */
@WebMvcTest(CapCheckController.class)
@Import({CapCheckController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
class CapCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CapCheckService capCheckService;

    private CapCalculationResult sampleResult(CapResultStatus status, List<com.susukkang.fgc.cap.dto.CapCheckDetailLine> details) {
        return new CapCalculationResult(
                1L, PaymentStage.GA_TO_FC, CapCheckKind.REALTIME, LocalDate.of(2026, 7, 10),
                500L, null,
                new BigDecimal("1200000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1200000"),
                new BigDecimal("650000"), new BigDecimal("550000"), new BigDecimal("54.166667"),
                status, details, Map.of());
    }

    @Test
    void triggersCalculationAndReturnsSavedResult() throws Exception {
        CapCheckSaveResult saved = new CapCheckSaveResult(999L, sampleResult(CapResultStatus.NORMAL, List.of()));
        given(capCheckService.calculateAndSave(any())).willReturn(saved);

        mockMvc.perform(post("/api/cap/checks")
                        .with(user("settle01").roles("SETTLEMENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractId":1,"paymentStage":"GA_TO_FC","asOfDate":"2026-07-10"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capCheckId").value(999))
                .andExpect(jsonPath("$.data.limitAmount").value(1200000))
                .andExpect(jsonPath("$.data.resultStatus").value("NORMAL"))
                .andExpect(jsonPath("$.error").isEmpty());
    }

    // REVIEW_REQUIRED 는 에러가 아니라 유효한 계산 결과이므로 200으로 응답한다
    @Test
    void returns200WithReviewRequiredStatusWhenAutoCalculationNotPossible() throws Exception {
        CapCheckSaveResult saved = new CapCheckSaveResult(1000L, sampleResult(CapResultStatus.REVIEW_REQUIRED, List.of()));
        given(capCheckService.calculateAndSave(any())).willReturn(saved);

        mockMvc.perform(post("/api/cap/checks")
                        .with(user("settle01").roles("SETTLEMENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractId":1,"paymentStage":"GA_TO_FC"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resultStatus").value("REVIEW_REQUIRED"));
    }

    @Test
    void rejectsTriggerRequestMissingContractId() throws Exception {
        mockMvc.perform(post("/api/cap/checks")
                        .with(user("settle01").roles("SETTLEMENT"))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentStage":"GA_TO_FC"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
    }

    @Test
    void triggerRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/cap/checks")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"contractId":1,"paymentStage":"GA_TO_FC"}
                                """))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void findsLatestSavedCapCheck() throws Exception {
        CapCheckSaveResult saved = new CapCheckSaveResult(999L, sampleResult(CapResultStatus.NORMAL, List.of()));
        given(capCheckService.findLatest(1L, PaymentStage.GA_TO_FC)).willReturn(Optional.of(saved));

        mockMvc.perform(get("/api/cap/checks/1")
                        .with(user("settle01").roles("SETTLEMENT"))
                        .param("paymentStage", "GA_TO_FC"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capCheckId").value(999))
                .andExpect(jsonPath("$.data.contractId").value(1));
    }

    @Test
    void returns404WhenNoCapCheckSavedYet() throws Exception {
        given(capCheckService.findLatest(1L, PaymentStage.GA_TO_FC)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/cap/checks/1")
                        .with(user("settle01").roles("SETTLEMENT"))
                        .param("paymentStage", "GA_TO_FC"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FGC-CAP-005"));
    }

    @Test
    void findLatestRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/cap/checks/1").param("paymentStage", "GA_TO_FC"))
                .andExpect(status().isUnauthorized());
    }
}
