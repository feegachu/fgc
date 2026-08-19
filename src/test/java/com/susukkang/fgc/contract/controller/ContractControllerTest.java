package com.susukkang.fgc.contract.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.config.SecurityConfig;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.PageResponse;
import com.susukkang.fgc.contract.domain.DataOrigin;
import com.susukkang.fgc.contract.dto.ContractCreateRequest;
import com.susukkang.fgc.contract.dto.ContractSearchCondition;
import com.susukkang.fgc.contract.dto.ContractUpdateRequest;
import com.susukkang.fgc.contract.dto.ContractView;
import com.susukkang.fgc.contract.dto.ContractCreateResponse;
import com.susukkang.fgc.contract.dto.ContractUpdateResponse;
import com.susukkang.fgc.contract.dto.ContractDetailResponse;
import com.susukkang.fgc.contract.dto.ContractScheduleResponse;
import com.susukkang.fgc.contract.service.ContractService;
import com.susukkang.fgc.schedule.dto.ScheduleHeaderResponse;
import com.susukkang.fgc.schedule.dto.ScheduleLineResponse;
import com.susukkang.fgc.schedule.service.ScheduleService;
import com.susukkang.fgc.arbitrage.service.ArbitrageService;
import com.susukkang.fgc.arbitrage.dto.ArbitrageCheckView;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static com.susukkang.fgc.contract.domain.ContractStatus.ACTIVE;
import static com.susukkang.fgc.contract.domain.PaymentCycleCode.MONTHLY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 설명 : 보험계약 API의 요청 처리와 응답 형식을 검증하는 컨트롤러 테스트
 * 보험계약 조회, 생성, 수정 API의 정상 응답과
 * 인증 실패 및 요청값 검증 실패를 확인한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-06
 */
@WebMvcTest(ContractController.class)
@Import({
        SecurityConfig.class,
        GlobalExceptionHandler.class,
        FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class,
        MessageSourceAutoConfiguration.class
})
class ContractControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ContractService contractService;

    @MockitoBean
    private ScheduleService scheduleService;

    @MockitoBean
    private ArbitrageService arbitrageService;

    @MockitoBean
    private CapCheckService capCheckService;

    @Test
    @DisplayName("계약별 지급단계 최신 1,200% 판정을 조회한다")
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
    @DisplayName("계약별 차익거래 검증 시계열을 조회한다")
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
                .andExpect(jsonPath("$.data[0].resultStatus").value("CLEAR"))
                .andExpect(jsonPath("$.data[0].resultStatusLabel").value("이상없음"))
                .andExpect(jsonPath("$.data[0].paymentStageLabel").value("GA→설계사"));
    }

    @Test
    @DisplayName("계약 ID로 운영용 예상 스케줄 목록을 조회한다")
    void getContractSchedulesReturnsSuccess() throws Exception {
        ScheduleHeaderResponse schedule = ScheduleHeaderResponse.builder()
                .scheduleHeaderId(100L)
                .contractNo("TEST-20260806-001")
                .build();

        ScheduleLineResponse line = ScheduleLineResponse.builder()
                .lineNo(1)
                .installmentNo(1)
                .build();
        when(scheduleService.selectByContractId(
                21L, com.susukkang.fgc.common.code.PaymentStage.INSURER_TO_GA))
                .thenReturn(ContractScheduleResponse.builder()
                        .headers(List.of(schedule))
                        .lines(List.of(line))
                        .build());

        mockMvc.perform(get("/api/v1/contracts/{contractId}/schedules", 21L)
                        .param("paymentStage", "INSURER_TO_GA")
                        .with(user("admin").roles("GA_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.headers[0].scheduleHeaderId").value(100))
                .andExpect(jsonPath("$.data.headers[0].contractNo").value("TEST-20260806-001"))
                .andExpect(jsonPath("$.data.lines[0].installmentNo").value(1));
    }

    @Test
    @DisplayName("보험계약 목록을 조회한다")
    void getContractsReturnsSuccess() throws Exception {
        ContractView contract = ContractView.builder()
                .contractNo("TEST-20260806-001")
                .insurerName("미래가상생명")
                .productName("가상 건강보장보험 A")
                .contractDate(LocalDate.of(2026, 8, 6))
                .monthlyEquivalentFirstPremium(new BigDecimal("100000"))
                .agentIdName("FC-0001 서본부")
                .contractStatus(ACTIVE)
                .capResultStatus(CapResultStatus.NORMAL)
                .dataOrigin(DataOrigin.MANUAL)
                .build();

        when(contractService.selectByCondition(
                any(ContractSearchCondition.class), eq(1), eq(20)
        )).thenReturn(PageResponse.of(
                List.of(contract), 1, 20, 1, "contractId,desc"
        ));

        mockMvc.perform(get("/api/v1/contracts")
                        .param("contractNo", "TEST")
                        .with(user("admin").roles("GA_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].contractNo").value("TEST-20260806-001"))
                .andExpect(jsonPath("$.data.content[0].insurerName").value("미래가상생명"))
                .andExpect(jsonPath("$.data.content[0].capResultStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.totalPages").value(1));

        // orgId는 선택 파라미터 — 미전달 시 검색 조건에 null로 바인딩된다 (IF-API-11)
        ArgumentCaptor<ContractSearchCondition> captor =
                ArgumentCaptor.forClass(ContractSearchCondition.class);
        verify(contractService).selectByCondition(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().getOrgId()).isNull();
    }
    @Test
    @DisplayName("orgId 요청 파라미터가 계약 목록 검색 조건에 바인딩된다")
    void getContractsBindsOrgIdCondition() throws Exception {
        // IF-API-11 · #168 — 조직(orgId) 검색 조건 API 계약 테스트
        when(contractService.selectByCondition(
                any(ContractSearchCondition.class), eq(1), eq(20)
        )).thenReturn(PageResponse.of(List.of(), 1, 20, 0, "contractId,desc"));

        mockMvc.perform(get("/api/v1/contracts")
                        .param("orgId", "7")
                        .with(user("admin").roles("GA_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));

        ArgumentCaptor<ContractSearchCondition> captor =
                ArgumentCaptor.forClass(ContractSearchCondition.class);
        verify(contractService).selectByCondition(captor.capture(), eq(1), eq(20));
        assertThat(captor.getValue().getOrgId()).isEqualTo(7L);
    }

    @Test
    @DisplayName("보험계약 상세정보를 조회한다")
    void getContractDetailReturnsSuccess() throws Exception {
        ContractDetailResponse detail =
                ContractDetailResponse.builder()
                        .contractNo("TEST-20260806-001")
                        .contractId(21L)
                        .insurerId(1L)
                        .insurerName("미래가상생명")
                        .productOfferingId(3L)
                        .productName("가상 건강보장보험 A")
                        .offeringVersion("2026-A")
                        .contractDate(LocalDate.of(2026, 8, 6))
                        .agentId(4L)
                        .agentName("김설계")
                        .organizationId(5L)
                        .organizationName("서울지사")
                        .premiumPerCycleAmount(
                                new BigDecimal("100000")
                        )
                        .paymentCycleCode(MONTHLY)
                        .firstPremiumAmount(
                                new BigDecimal("100000")
                        )
                        .monthlyEquivalentFirstPremium(
                                new BigDecimal("100000")
                        )
                        .paymentTermMonths(120)
                        .standardSurrenderDeductionAmount(
                                new BigDecimal("50000")
                        )
                        .contractStatus(ACTIVE)
                        .dataOrigin(DataOrigin.MANUAL)
                        .build();

        when(contractService.selectContractDetailById(21L))
                .thenReturn(detail);

        mockMvc.perform(
                        get("/api/v1/contracts/{id}", 21L)
                                .with(
                                        user("settlement")
                                                .roles("SETTLEMENT")
                                )
                )
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath("$.data.contractNo")
                                .value("TEST-20260806-001")
                )
                .andExpect(
                        jsonPath("$.data.productName")
                                .value("가상 건강보장보험 A")
                )
                .andExpect(jsonPath("$.data.insurerId").value(1))
                .andExpect(jsonPath("$.data.productOfferingId").value(3))
                .andExpect(jsonPath("$.data.agentId").value(4))
                .andExpect(jsonPath("$.data.organizationId").value(5));
    }

    @Test
    @DisplayName("계약 상태 사건과 Job별 처리 이력을 조회한다")
    void getContractStatusEventsReturnsSuccess() throws Exception {
        OffsetDateTime effectiveAt = OffsetDateTime.parse("2026-07-15T00:00:00+09:00");
        OffsetDateTime receivedAt = OffsetDateTime.parse("2026-07-16T06:00:00+09:00");
        OffsetDateTime processedAt = OffsetDateTime.parse("2026-07-16T06:30:00+09:00");
        when(contractService.selectStatusEventsByContractId(21L)).thenReturn(List.of(
                new com.susukkang.fgc.contract.dto.ContractStatusEventResponse(
                        2, ACTIVE, com.susukkang.fgc.contract.domain.ContractStatus.TERMINATED,
                        effectiveAt, receivedAt,
                        List.of(new com.susukkang.fgc.contract.dto.ContractStatusEventProcessingResponse(
                                "DailyChangedContractJob", "SUCCEEDED", processedAt)),
                        "INSURER_FEED", "EVENT-21-2"
                )
        ));

        mockMvc.perform(get("/api/v1/contracts/{id}/status-events", 21L)
                        .with(user("settlement").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].eventSeq").value(2))
                .andExpect(jsonPath("$.data[0].previousStatus").value("ACTIVE"))
                .andExpect(jsonPath("$.data[0].newStatus").value("TERMINATED"))
                .andExpect(jsonPath("$.data[0].effectiveAt").value("2026-07-15T00:00:00+09:00"))
                .andExpect(jsonPath("$.data[0].receivedAt").value("2026-07-16T06:00:00+09:00"))
                .andExpect(jsonPath("$.data[0].processings[0].processingJob")
                        .value("DailyChangedContractJob"))
                .andExpect(jsonPath("$.data[0].processings[0].processingStatus").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data[0].processings[0].processedAt")
                        .value("2026-07-16T06:30:00+09:00"))
                .andExpect(jsonPath("$.data[0].sourceSystem").value("INSURER_FEED"))
                .andExpect(jsonPath("$.data[0].sourceEventKey").value("EVENT-21-2"));
    }

    @Test
    @DisplayName("보험계약을 생성한다")
    void createContractReturnsSuccess() throws Exception {
        when(contractService.createContract(any(ContractCreateRequest.class)))
                .thenReturn(ContractCreateResponse.builder()
                        .contractId(21L)
                        .scheduleHeaderIds(java.util.List.of(101L, 102L))
                        .build());

        mockMvc.perform(post("/api/v1/contracts")
                        .with(user("settlement").roles("SETTLEMENT"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractId").value(21))
                .andExpect(jsonPath("$.data.scheduleHeaderIds[0]").value(101))
                .andExpect(jsonPath("$.data.regeneratedScheduleIds").doesNotExist());
    }

    @Test
    @DisplayName("일시납과 0원 경계값으로 보험계약을 생성할 수 있다")
    void createContractAcceptsSinglePaymentAndZeroPremiums() throws Exception {
        ContractCreateRequest request = createRequest();
        request.setPaymentCycleCode(com.susukkang.fgc.contract.domain.PaymentCycleCode.SINGLE);
        request.setPremiumPerCycleAmount(BigDecimal.ZERO);
        request.setFirstPremiumAmount(BigDecimal.ZERO);
        request.setMonthlyEquivalentFirstPremium(BigDecimal.ZERO);
        when(contractService.createContract(any(ContractCreateRequest.class)))
                .thenReturn(ContractCreateResponse.builder().contractId(21L).build());

        mockMvc.perform(post("/api/v1/contracts")
                        .with(user("settlement").roles("SETTLEMENT"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractId").value(21));
    }

    @Test
    @DisplayName("주기 보험료가 없으면 계약 생성을 거절한다")
    void createContractRejectsMissingPremiumPerCycle() throws Exception {
        ContractCreateRequest request = createRequest();
        request.setPremiumPerCycleAmount(null);

        mockMvc.perform(post("/api/v1/contracts")
                        .with(user("settlement").roles("SETTLEMENT"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.field").value("premiumPerCycleAmount"));
    }

    /** FGC-FUN-002 — SYSTEM_ADMIN 은 "전부"(화면정의서 §4-1)라 계약 등록·수정도 허용된다. */
    @Test
    @DisplayName("SYSTEM_ADMIN 도 보험계약을 생성할 수 있다")
    void createContractAllowsSystemAdmin() throws Exception {
        when(contractService.createContract(any(ContractCreateRequest.class)))
                .thenReturn(ContractCreateResponse.builder().contractId(21L).build());

        mockMvc.perform(post("/api/v1/contracts")
                        .with(user("admin").roles("SYSTEM_ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractId").value(21));
    }

    /** FGC-FUN-002 — SYSTEM_ADMIN 수정 허용 회귀 방지. */
    @Test
    @DisplayName("SYSTEM_ADMIN 도 보험계약을 수정할 수 있다")
    void updateContractAllowsSystemAdmin() throws Exception {
        when(contractService.updateContract(
                eq(21L),
                any(ContractUpdateRequest.class)
        )).thenReturn(ContractUpdateResponse.builder()
                .contractId(21L)
                .scheduleHeaderIds(java.util.List.of(201L, 202L))
                .regeneratedScheduleIds(java.util.List.of(201L, 202L))
                .build());

        mockMvc.perform(put("/api/v1/contracts/{id}", 21L)
                        .with(user("admin").roles("SYSTEM_ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractId").value(21))
                .andExpect(jsonPath("$.data.scheduleHeaderIds[0]").value(201))
                .andExpect(jsonPath("$.data.regeneratedScheduleIds[0]").value(201))
                .andExpect(jsonPath("$.data.scheduleHeaderIds[1]").value(202));
    }

    @Test
    @DisplayName("보험계약을 수정한다")
    void updateContractReturnsSuccess() throws Exception {
        when(contractService.updateContract(
                eq(21L),
                any(ContractUpdateRequest.class)
        )).thenReturn(ContractUpdateResponse.builder().contractId(21L).build());

        mockMvc.perform(put("/api/v1/contracts/{id}", 21L)
                        .with(user("settlement").roles("SETTLEMENT"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractId").value(21));
    }

    private ContractCreateRequest createRequest() {
        return new ContractCreateRequest(
                1L,
                "TEST-20260806-001",
                1L,
                LocalDate.of(2026, 8, 6),
                ACTIVE,
                1L,
                4L,
                MONTHLY,
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                120,
                new BigDecimal("50000")
        );
    }

    private ContractUpdateRequest updateRequest() {
        return new ContractUpdateRequest(
                "TEST-20260806-001",
                3L,
                4L,
                LocalDate.of(2026, 8, 6),
                ACTIVE,
                1L,
                4L,
                MONTHLY,
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                new BigDecimal("100000"),
                120,
                new BigDecimal("50000")
        );
    }

    @Test
    @DisplayName("정산담당자가 아니면 보험계약을 생성할 수 없다")
    void createContractRejectsNonSettlementUser() throws Exception {
        mockMvc.perform(post("/api/v1/contracts")
                        .with(user("admin").roles("GA_ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                createRequest()
                        )))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("정산담당자가 아니면 보험계약을 수정할 수 없다")
    void updateContractRejectsNonSettlementUser() throws Exception {
        mockMvc.perform(put("/api/v1/contracts/{id}", 21L)
                        .with(user("admin").roles("GA_ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                updateRequest()
                        )))
                .andExpect(status().isForbidden());
    }

    /**
     * FUN-002(#82) — COMPLIANCE는 §4-1 "조회만"이라 계약 생성·수정 모두 403이어야 한다.
     * 이 거부는 SecurityConfig 굵은 규칙(필터 단계)에서 나므로, ApiResponse 봉투는
     * 컨트롤러 advice 가 아니라 apiAccessDeniedHandler 가 써 준다 — 봉투까지 확인한다.
     */
    @Test
    @DisplayName("준법·감사는 보험계약을 생성할 수 없다")
    void createContractRejectsComplianceUser() throws Exception {
        mockMvc.perform(post("/api/v1/contracts")
                        .with(user("comp01").roles("COMPLIANCE"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                createRequest()
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FGC-AUTH-003"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    @DisplayName("준법·감사는 보험계약을 수정할 수 없다")
    void updateContractRejectsComplianceUser() throws Exception {
        mockMvc.perform(put("/api/v1/contracts/{id}", 21L)
                        .with(user("comp01").roles("COMPLIANCE"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                updateRequest()
                        )))
                .andExpect(status().isForbidden());
    }

    /** FUN-002(#82) — 차익거래 수동 검증도 CAN_PROCESS(SETTLEMENT·SYSTEM_ADMIN) 전용이다. */
    @Test
    @DisplayName("준법·감사는 차익거래 수동 검증을 실행할 수 없다")
    void reArbitrageCheckRejectsComplianceUser() throws Exception {
        mockMvc.perform(post("/api/v1/contracts/{id}/arbitrage-check", 21L)
                        .with(user("comp01").roles("COMPLIANCE"))
                        .contentType(APPLICATION_JSON)
                        .content("{\"asOfDate\":\"2026-08-13\",\"reason\":\"정기 점검\"}"))
                .andExpect(status().isForbidden());
    }
}
