package com.susukkang.fgc.cap.controller;

import com.susukkang.fgc.cap.dto.CapCheckBasisResponse;
import com.susukkang.fgc.cap.dto.CapCheckDetailResponse;
import com.susukkang.fgc.cap.dto.CapCheckItemResponse;
import com.susukkang.fgc.cap.dto.CapCheckListRow;
import com.susukkang.fgc.cap.dto.CapCheckSearchResult;
import com.susukkang.fgc.cap.dto.CapCheckSummary;
import com.susukkang.fgc.cap.dto.CapAgentSummaryRow;
import com.susukkang.fgc.cap.dto.CapStageSummaryRow;
import com.susukkang.fgc.cap.dto.CapCheckSearchCriteria;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.common.web.PageResponse;
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
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CapCheckController(IF-API-30, FUN-030) API 통합테스트. CapCheckService 는 mock 으로 대체해
 * 컨트롤러의 요청·응답 변환, 인증, SIR-008 표시형식만 검증한다 — 계산식 자체는 CapCalculatorImplTest,
 * 검색 SQL 은 CapCheckMapper 통합테스트가 담당한다.
 */
@WebMvcTest(CapCheckController.class)
@Import({CapCheckController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
class CapCheckControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CapCheckService capCheckService;

    private CapCheckListRow sampleListRow(CapResultStatus status) {
        CapCheckListRow row = new CapCheckListRow();
        row.setCapCheckId(999L);
        row.setContractId(1L);
        row.setContractNo("C001");
        row.setPaymentStage("GA_TO_FC");
        row.setAsOfDate(LocalDate.of(2026, 7, 10));
        row.setBasePremiumAmount(new BigDecimal("1200000"));
        row.setRefund12mAmount(BigDecimal.ZERO);
        row.setComplianceDeductionAmount(BigDecimal.ZERO);
        row.setLimitAmount(new BigDecimal("1200000"));
        row.setIncludedAmount(new BigDecimal("650000"));
        row.setRemainingAmount(new BigDecimal("550000"));
        row.setUsagePct(new BigDecimal("54.166667"));
        row.setResultStatus(status.name());
        row.setCapRuleSetId(500L);
        return row;
    }

    @Test
    void searchReturnsSummaryAndSirFormattedContent() throws Exception {
        CapStageSummaryRow insurerStage = new CapStageSummaryRow();
        insurerStage.setPaymentStage("INSURER_TO_GA");
        insurerStage.setContractCount(2);
        insurerStage.setLimitAmountTotal(new BigDecimal("2400000"));
        insurerStage.setIncludedAmountTotal(new BigDecimal("1800000"));
        insurerStage.setComplianceDeductionAmountTotal(new BigDecimal("30000"));
        insurerStage.setUsagePct(new BigDecimal("75.000000"));
        insurerStage.setViolationCount(1);
        insurerStage.setWarningCount(0);
        insurerStage.setWorstContractNo("C004");
        insurerStage.setWorstUsagePct(new BigDecimal("104.166667"));

        CapAgentSummaryRow agent = new CapAgentSummaryRow();
        agent.setAgentId(11L);
        agent.setAgentCode("FC-001");
        agent.setAgentName("김설계");
        agent.setOrganizationId(21L);
        agent.setOrganizationCode("BR-001");
        agent.setOrganizationName("서울지점");
        agent.setContractCount(1);
        agent.setLimitAmountTotal(new BigDecimal("1200000"));
        agent.setIncludedAmountTotal(new BigDecimal("650000"));
        agent.setUsagePct(new BigDecimal("54.166667"));

        CapCheckSearchResult searchResult = new CapCheckSearchResult(
                new CapCheckSummary(3, 1, 1, 0),
                List.of(insurerStage),
                List.of(agent),
                PageResponse.of(List.of(sampleListRow(CapResultStatus.NORMAL)), 1, 20, 1, "asOfDate,desc"));
        given(capCheckService.search(any(), anyInt(), anyInt())).willReturn(searchResult);

        mockMvc.perform(get("/api/v1/cap-checks").with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.normal").value(3))
                .andExpect(jsonPath("$.data.summary.violation").value(1))
                .andExpect(jsonPath("$.data.stageSummary[0].paymentStage").value("INSURER_TO_GA"))
                .andExpect(jsonPath("$.data.stageSummary[0].complianceDeductionAmountTotal").value(30000))
                .andExpect(jsonPath("$.data.stageSummary[0].usagePct").value("75.000000"))
                .andExpect(jsonPath("$.data.agentSummary[0].agentName").value("김설계"))
                .andExpect(jsonPath("$.data.agentSummary[0].organizationName").value("서울지점"))
                .andExpect(jsonPath("$.data.agentSummary[0].usagePct").value("54.166667"))
                .andExpect(jsonPath("$.data.content[0].capCheckId").value(999))
                .andExpect(jsonPath("$.data.content[0].limitAmount").value(1200000))
                .andExpect(jsonPath("$.data.content[0].usagePct").value("54.166667"))
                .andExpect(jsonPath("$.data.content[0].resultStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.content[0].resultStatusLabel").value("정상"))
                .andExpect(jsonPath("$.data.content[0].paymentStageLabel").value("GA→설계사"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void searchBindsOrgIdIntoOrganizationCriteria() throws Exception {
        given(capCheckService.search(any(), anyInt(), anyInt())).willReturn(
                new CapCheckSearchResult(
                        new CapCheckSummary(0, 0, 0, 0),
                        List.of(),
                        List.of(),
                        PageResponse.of(List.of(), 1, 20, 0, "asOfDate,desc")));

        mockMvc.perform(get("/api/v1/cap-checks")
                        .param("orgId", "21")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk());

        org.mockito.ArgumentCaptor<CapCheckSearchCriteria> criteriaCaptor =
                org.mockito.ArgumentCaptor.forClass(CapCheckSearchCriteria.class);
        verify(capCheckService).search(criteriaCaptor.capture(), org.mockito.ArgumentMatchers.eq(1),
                org.mockito.ArgumentMatchers.eq(20));
        org.assertj.core.api.Assertions.assertThat(criteriaCaptor.getValue().organizationId()).isEqualTo(21L);
    }

    @Test
    void searchRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/cap-checks")).andExpect(status().isUnauthorized());
    }

    // page/size 범위 검증은 CapCheckServiceImpl이 담당하지만(단위테스트에서 직접 검증),
    // 컨트롤러를 거쳐 GlobalExceptionHandler까지 400으로 잘 이어지는지도 확인한다.
    @Test
    void returns400WhenPageOrSizeOutOfRange() throws Exception {
        given(capCheckService.search(any(), anyInt(), anyInt()))
                .willThrow(new FgcBusinessException(FgcErrorCode.COMMON_002, "page",
                        java.util.Map.of("field", "page"), null));

        mockMvc.perform(get("/api/v1/cap-checks").param("page", "0")
                        .with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
    }

    private CapCheckBasisResponse sampleBasisResponse() {
        List<CapCheckDetailResponse> details = List.of(
                new CapCheckDetailResponse(1, "FC 기본수수료", "INCLUDED", 650000L, "산입", null),
                new CapCheckDetailResponse(2, "교육비", "EXCLUDED", 50000L, "제외", "SRC-004"));

        // basePremiumAmount는 월납 원액(100,000)이다 — limitAmount(1,200,000)와는 다른 값이다.
        CapCheckItemResponse capCheck = new CapCheckItemResponse(
                999L, 1L, "C001", PaymentStage.GA_TO_FC, PaymentStage.GA_TO_FC.label(),
                LocalDate.of(2026, 7, 10),
                100000L, 0L, 0L, 1200000L, 650000L, 550000L, "54.166667",
                CapResultStatus.NORMAL, CapResultStatus.NORMAL.label(), 500L);

        return new CapCheckBasisResponse(capCheck, details, Map.of("premiumMultiplier", "12.0000"));
    }

    @Test
    void findDetailReturnsNestedCapCheckDetailsAndCalculationSnapshot() throws Exception {
        given(capCheckService.findDetail(999L)).willReturn(Optional.of(sampleBasisResponse()));

        mockMvc.perform(get("/api/v1/cap-checks/999/details").with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capCheck.capCheckId").value(999))
                .andExpect(jsonPath("$.data.capCheck.contractNo").value("C001"))
                .andExpect(jsonPath("$.data.capCheck.capRuleSetId").value(500))
                .andExpect(jsonPath("$.data.capCheck.basePremiumAmount").value(100000))
                .andExpect(jsonPath("$.data.capCheck.limitAmount").value(1200000))
                .andExpect(jsonPath("$.data.capCheck.includedAmount").value(650000))
                .andExpect(jsonPath("$.data.capCheck.usagePct").value("54.166667"))
                .andExpect(jsonPath("$.data.calculationSnapshot.premiumMultiplier").value("12.0000"))
                .andExpect(jsonPath("$.data.details[0].commissionItemName").value("FC 기본수수료"))
                .andExpect(jsonPath("$.data.details[0].classificationSnapshot").value("INCLUDED"))
                .andExpect(jsonPath("$.data.details[1].evidenceRef").value("SRC-004"));
    }

    @Test
    void findDetailReturns404WhenCapCheckIdDoesNotExist() throws Exception {
        given(capCheckService.findDetail(999L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/v1/cap-checks/999/details").with(user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-004"));
    }

    @Test
    void findDetailRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/cap-checks/999/details")).andExpect(status().isUnauthorized());
    }
}
