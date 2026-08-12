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
import com.susukkang.fgc.contract.dto.ContractResponse;
import com.susukkang.fgc.contract.dto.ContractDetailResponse;
import com.susukkang.fgc.contract.dto.ContractScheduleResponse;
import com.susukkang.fgc.contract.service.ContractService;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static com.susukkang.fgc.contract.domain.ContractStatus.ACTIVE;
import static com.susukkang.fgc.contract.domain.PaymentCycleCode.MONTHLY;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
    }
    @Test
    @DisplayName("보험계약 상세정보를 조회한다")
    void getContractDetailReturnsSuccess() throws Exception {
        ContractDetailResponse detail =
                ContractDetailResponse.builder()
                        .contractNo("TEST-20260806-001")
                        .productName("가상 건강보장보험 A")
                        .contractDate(LocalDate.of(2026, 8, 6))
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
                );
    }

    @Test
    @DisplayName("보험계약을 생성한다")
    void createContractReturnsSuccess() throws Exception {
        when(contractService.createContract(any(ContractCreateRequest.class)))
                .thenReturn(ContractResponse.builder().contractId(21L).build());

        mockMvc.perform(post("/api/v1/contracts")
                        .with(user("settlement").roles("SETTLEMENT"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractId").value(21));
    }

    /** FGC-FUN-002 — SYSTEM_ADMIN 은 "전부"(화면정의서 §4-1)라 계약 등록·수정도 허용된다. */
    @Test
    @DisplayName("SYSTEM_ADMIN 도 보험계약을 생성할 수 있다")
    void createContractAllowsSystemAdmin() throws Exception {
        when(contractService.createContract(any(ContractCreateRequest.class)))
                .thenReturn(ContractResponse.builder().contractId(21L).build());

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
        )).thenReturn(ContractResponse.builder().contractId(21L).build());

        mockMvc.perform(put("/api/v1/contracts/{id}", 21L)
                        .with(user("admin").roles("SYSTEM_ADMIN"))
                        .contentType(APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.contractId").value(21));
    }

    @Test
    @DisplayName("보험계약을 수정한다")
    void updateContractReturnsSuccess() throws Exception {
        when(contractService.updateContract(
                eq(21L),
                any(ContractUpdateRequest.class)
        )).thenReturn(ContractResponse.builder().contractId(21L).build());

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
}
