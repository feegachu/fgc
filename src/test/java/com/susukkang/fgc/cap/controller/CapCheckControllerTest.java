package com.susukkang.fgc.cap.controller;

import com.susukkang.fgc.cap.dto.CapCalculationResult;
import com.susukkang.fgc.cap.dto.CapCheckDetailLine;
import com.susukkang.fgc.cap.dto.CapCheckListRow;
import com.susukkang.fgc.cap.dto.CapCheckSaveResult;
import com.susukkang.fgc.cap.dto.CapCheckSearchResult;
import com.susukkang.fgc.cap.dto.CapCheckSummary;
import com.susukkang.fgc.cap.service.CapCheckService;
import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * CapCheckController API 통합테스트. CapCheckService 는 mock 으로 대체해 컨트롤러의
 * 요청·응답 변환, 인증, SIR-008 표시형식만 검증한다 — 계산식 자체는 CapCalculatorImplTest,
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

    private CapCheckSaveResult sampleSaveResult(CapResultStatus status, List<CapCheckDetailLine> details) {
        CapCalculationResult result = new CapCalculationResult(
                1L, PaymentStage.GA_TO_FC, CapCheckKind.REALTIME, LocalDate.of(2026, 7, 10),
                500L, null,
                new BigDecimal("1200000"), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal("1200000"),
                new BigDecimal("650000"), new BigDecimal("550000"), new BigDecimal("54.166667"),
                status, details, Map.of("premiumMultiplier", "12.0000"));
        return new CapCheckSaveResult(999L, result);
    }

    @Test
    void searchReturnsSummaryAndSirFormattedContent() throws Exception {
        CapCheckSearchResult searchResult = new CapCheckSearchResult(
                new CapCheckSummary(3, 1, 1, 0),
                PageResponse.of(List.of(sampleListRow(CapResultStatus.NORMAL)), 1, 20, 1, "asOfDate,desc"));
        given(capCheckService.search(any(), anyInt(), anyInt())).willReturn(searchResult);

        mockMvc.perform(get("/api/cap/checks")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.summary.normal").value(3))
                .andExpect(jsonPath("$.data.summary.violation").value(1))
                .andExpect(jsonPath("$.data.content[0].capCheckId").value(999))
                .andExpect(jsonPath("$.data.content[0].limitAmount").value(1200000))
                .andExpect(jsonPath("$.data.content[0].usagePct").value("54.166667"))
                .andExpect(jsonPath("$.data.content[0].resultStatus").value("NORMAL"))
                .andExpect(jsonPath("$.data.content[0].resultStatusLabel").value("정상"))
                .andExpect(jsonPath("$.data.content[0].paymentStageLabel").value("GA→설계사"))
                .andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void searchRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/cap/checks")).andExpect(status().isUnauthorized());
    }

    @Test
    void findDetailsReturnsCapCheckAndDetailLines() throws Exception {
        CapCheckDetailLine detail = new CapCheckDetailLine(
                1, 1L, "BASE_COMMISSION", "FC 기본수수료", 11L, 1,
                "INCLUDED", new BigDecimal("650000"), "산입", null);
        given(capCheckService.findById(999L))
                .willReturn(Optional.of(sampleSaveResult(CapResultStatus.NORMAL, List.of(detail))));

        mockMvc.perform(get("/api/cap/checks/999/details")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.capCheck.capCheckId").value(999))
                .andExpect(jsonPath("$.data.details[0].commissionItemName").value("FC 기본수수료"))
                .andExpect(jsonPath("$.data.details[0].classificationSnapshot").value("INCLUDED"))
                .andExpect(jsonPath("$.data.details[0].classificationSnapshotLabel").value("산입"))
                .andExpect(jsonPath("$.data.details[0].amount").value(650000))
                .andExpect(jsonPath("$.data.calculationSnapshot.premiumMultiplier").value("12.0000"));
    }

    @Test
    void findDetailsReturns500WhenCapCheckIdDoesNotExist() throws Exception {
        given(capCheckService.findById(1234L)).willReturn(Optional.empty());

        mockMvc.perform(get("/api/cap/checks/1234/details")
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .user("settle01").roles("SETTLEMENT")))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-500"));
    }

    @Test
    void findDetailsRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/cap/checks/999/details")).andExpect(status().isUnauthorized());
    }
}
