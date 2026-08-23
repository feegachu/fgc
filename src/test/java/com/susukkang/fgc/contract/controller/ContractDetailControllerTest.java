package com.susukkang.fgc.contract.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckView;
import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckRequest;
import com.susukkang.fgc.arbitrage.dto.ReArbitrageCheckResponse;
import com.susukkang.fgc.arbitrage.service.ArbitrageService;
import com.susukkang.fgc.auth.dto.AppUserView;
import com.susukkang.fgc.auth.dto.FgcUserDetails;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.contract.dto.ContractDetailResponse;
import com.susukkang.fgc.contract.dto.ContractScheduleResponse;
import com.susukkang.fgc.contract.dto.ContractTransactionTabResponse;
import com.susukkang.fgc.contract.service.ContractJournalProjectionService;
import com.susukkang.fgc.contract.service.ContractService;
import com.susukkang.fgc.contract.service.ContractTransactionProjectionService;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse;
import com.susukkang.fgc.schedule.dto.ScheduleLineResponse;
import com.susukkang.fgc.schedule.service.ScheduleService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContractDetailController.class)
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class,
        MessageSourceAutoConfiguration.class
})
class ContractDetailControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;

    @MockitoBean ContractService contractService;
    @MockitoBean ScheduleService scheduleService;
    @MockitoBean CapCheckService capCheckService;
    @MockitoBean ArbitrageService arbitrageService;
    @MockitoBean ContractJournalProjectionService contractJournalProjectionService;
    @MockitoBean ContractTransactionProjectionService contractTransactionProjectionService;

    @Test
    @DisplayName("계약 상세 기본정보를 조회한다")
    void getContractDetailReturnsSuccess() throws Exception {
        when(contractService.selectContractDetailById(21L))
                .thenReturn(ContractDetailResponse.builder()
                        .contractId(21L)
                        .contractNo("TEST-20260806-001")
                        .productName("가상 건강보장보험 A")
                        .build());

        mockMvc.perform(get("/api/v1/contracts/{id}", 21L)
                        .with(user("settlement").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractId").value(21))
                .andExpect(jsonPath("$.data.contractNo").value("TEST-20260806-001"))
                .andExpect(jsonPath("$.data.productName").value("가상 건강보장보험 A"));
    }

    @Test
    @DisplayName("계약 상태 이력을 조회한다")
    void getContractStatusEventsReturnsSuccess() throws Exception {
        when(contractService.selectStatusEventsByContractId(21L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/contracts/{id}/status-events", 21L)
                        .with(user("settlement").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());

        verify(contractService).selectStatusEventsByContractId(21L);
    }

    @Test
    @DisplayName("계약의 운영 예상 스케줄을 조회한다")
    void getContractSchedulesReturnsSuccess() throws Exception {
        when(scheduleService.selectByContractId(21L, PaymentStage.INSURER_TO_GA))
                .thenReturn(ContractScheduleResponse.builder()
                        .headers(List.of(ScheduleHeaderResponse.builder()
                                .scheduleHeaderId(100L)
                                .contractNo("TEST-20260806-001")
                                .build()))
                        .lines(List.of(ScheduleLineResponse.builder()
                                .lineNo(1)
                                .installmentNo(1)
                                .build()))
                        .build());

        mockMvc.perform(get("/api/v1/contracts/{id}/schedules", 21L)
                        .param("paymentStage", "INSURER_TO_GA")
                        .with(user("admin").roles("GA_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headers[0].scheduleHeaderId").value(100))
                .andExpect(jsonPath("$.data.lines[0].installmentNo").value(1));
    }

    @Test
    @DisplayName("계약의 지급단계별 최신 1,200% 판정을 조회한다")
    void getContractCapChecksReturnsSuccess() throws Exception {
        when(capCheckService.findLatest(eq(21L), any(PaymentStage.class)))
                .thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/contracts/{id}/cap-checks", 21L)
                        .with(user("admin").roles("GA_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("처리 권한 사용자는 계약 한도를 수동 재검증할 수 있다")
    void recheckCapReturnsSuccess() throws Exception {
        when(contractService.recheckCap(21L)).thenReturn(List.of());

        mockMvc.perform(post("/api/v1/contracts/{id}/cap-check", 21L)
                        .with(user("settlement").roles("SETTLEMENT"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    @DisplayName("처리 권한 사용자는 계약 스케줄을 새 버전으로 재생성할 수 있다")
    void regenerateSchedulesReturnsSuccess() throws Exception {
        when(contractService.regenerateSchedules(21L, "CONTRACT_DETAIL_MANUAL"))
                .thenReturn(List.of(301L, 302L));

        mockMvc.perform(post("/api/v1/contracts/{id}/schedules/regenerate", 21L)
                        .param("reason", "CONTRACT_DETAIL_MANUAL")
                        .with(user("settlement").roles("SETTLEMENT"))
                        .with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0]").value(301))
                .andExpect(jsonPath("$.data[1]").value(302));
    }

    @Test
    @DisplayName("계약의 차익거래 검증 시계열을 조회한다")
    void getContractArbitrageChecksReturnsSuccess() throws Exception {
        when(arbitrageService.selectByContractId(21L, PaymentStage.GA_TO_FC))
                .thenReturn(List.of(ArbitrageCheckView.builder()
                        .arbitrageCheckId(100L)
                        .contractId(21L)
                        .paymentStage(PaymentStage.GA_TO_FC)
                        .resultStatus(ArbitrageCheckStatus.CLEAR)
                        .build()));

        mockMvc.perform(get("/api/v1/contracts/{id}/arbitrage-checks", 21L)
                        .with(user("admin").roles("GA_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].arbitrageCheckId").value(100))
                .andExpect(jsonPath("$.data[0].resultStatus").value("CLEAR"));
    }

    @Test
    @DisplayName("처리 권한 사용자는 계약 차익거래 검증을 실행할 수 있다")
    void reArbitrageCheckReturnsSuccess() throws Exception {
        ReArbitrageCheckRequest request = new ReArbitrageCheckRequest(
                java.time.LocalDate.of(2026, 8, 13), "정기 점검");
        when(arbitrageService.reArbitrageCheck(eq(21L), any(ReArbitrageCheckRequest.class), eq(1L)))
                .thenReturn(ReArbitrageCheckResponse.builder()
                        .arbitrageCheckId(401L)
                        .resultStatus(ArbitrageCheckStatus.CLEAR)
                        .validationRunId(501L)
                        .build());

        mockMvc.perform(post("/api/v1/contracts/{id}/arbitrage-check", 21L)
                        .with(user(userDetails("SETTLEMENT")))
                        .with(csrf())
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.arbitrageCheckId").value(401))
                .andExpect(jsonPath("$.data.validationRunId").value(501));
    }

    @Test
    @DisplayName("준법·감사는 계약 상세 수동 검증을 실행할 수 없다")
    void detailActionRejectsComplianceUser() throws Exception {
        mockMvc.perform(post("/api/v1/contracts/{id}/cap-check", 21L)
                        .with(user("comp01").roles("COMPLIANCE"))
                        .with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("계약에 연결된 검증원장을 조회한다")
    void getContractJournalsReturnsSuccess() throws Exception {
        when(contractJournalProjectionService.findJournalsByContractId(21L)).thenReturn(List.of());

        mockMvc.perform(get("/api/v1/contracts/{id}/journals", 21L)
                        .with(user("settlement").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        verify(contractJournalProjectionService).findJournalsByContractId(21L);
    }

    @Test
    @DisplayName("계약에 연결된 지급 건과 대사 결과를 조회한다")
    void getContractTransactionsReturnsSuccess() throws Exception {
        when(contractTransactionProjectionService.findTransactionsByContractId(21L))
                .thenReturn(new ContractTransactionTabResponse(List.of(), List.of()));

        mockMvc.perform(get("/api/v1/contracts/{id}/transactions", 21L)
                        .with(user("settlement").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transactions").isArray())
                .andExpect(jsonPath("$.data.reconciliations").isArray());
    }

    private static FgcUserDetails userDetails(String role) {
        AppUserView user = new AppUserView();
        user.setUserId(1L);
        user.setLoginId("settle01");
        user.setPasswordHash("x");
        user.setUserName("테스트 사용자");
        user.setRoleCode(role);
        user.setAccountStatus("ACTIVE");
        return new FgcUserDetails(user, true, true);
    }
}
