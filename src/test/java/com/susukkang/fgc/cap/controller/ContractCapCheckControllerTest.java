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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.BDDMockito.given;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** ContractCapCheckController(IF-API-14) 통합테스트 — 두 지급단계를 합산 없이 그대로 돌려주는지 확인한다. */
@WebMvcTest(ContractCapCheckController.class)
@Import({ContractCapCheckController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
class ContractCapCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CapCheckService capCheckService;

    private CapCheckSaveResult sampleSaveResult(PaymentStage stage) {
        CapCalculationResult result = new CapCalculationResult(
                1L, stage, CapCheckKind.REALTIME, LocalDate.of(2026, 7, 10),
                500L, null,
                new BigDecimal("1200000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1200000"),
                new BigDecimal("650000"), new BigDecimal("550000"), new BigDecimal("54.166667"),
                CapResultStatus.NORMAL, List.of(), Map.of());
        return new CapCheckSaveResult(999L, result);
    }

    @Test
    void returnsBothPaymentStagesWithoutSumming() throws Exception {
        given(capCheckService.findByContract(1L)).willReturn(List.of(
                sampleSaveResult(PaymentStage.GA_TO_FC), sampleSaveResult(PaymentStage.INSURER_TO_GA)));

        mockMvc.perform(get("/api/contracts/1/cap/checks").with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].paymentStage").value("GA_TO_FC"))
                .andExpect(jsonPath("$.data[1].paymentStage").value("INSURER_TO_GA"));
    }

    @Test
    void returnsEmptyArrayWhenNoCapCheckCalculatedYet() throws Exception {
        given(capCheckService.findByContract(2L)).willReturn(List.of());

        mockMvc.perform(get("/api/contracts/2/cap/checks").with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/contracts/1/cap/checks")).andExpect(status().isUnauthorized());
    }
}
