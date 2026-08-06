package com.susukkang.fgc.dashboard.controller;

import com.susukkang.fgc.common.exception.ConstraintErrorCodeResolver;
import com.susukkang.fgc.common.exception.FgcMessageResolver;
import com.susukkang.fgc.common.exception.GlobalExceptionHandler;
import com.susukkang.fgc.dashboard.dto.DashboardKpiCounts;
import com.susukkang.fgc.dashboard.dto.DashboardSummaryResult;
import com.susukkang.fgc.dashboard.dto.RecentExceptionRow;
import com.susukkang.fgc.dashboard.dto.RecentValidationRunRow;
import com.susukkang.fgc.dashboard.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.context.MessageSourceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * DashboardController(IF-API-03, FUN-057) API 통합테스트
 * DashboardService는 mock으로 대체해
 * 컨트롤러의 요청·응답 변환, month 검증, 인증만 검증
 */
@WebMvcTest(DashboardController.class)
@Import({DashboardController.class, GlobalExceptionHandler.class, FgcMessageResolver.class,
        ConstraintErrorCodeResolver.class, MessageSourceAutoConfiguration.class})
class DashboardControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardService dashboardService;

    private DashboardSummaryResult sampleResult(LocalDate month) {
        DashboardKpiCounts kpis = new DashboardKpiCounts(1L, 2L, 3L, 4L, 5L, 6L);
        List<RecentExceptionRow> exceptions = List.of(
                new RecentExceptionRow(10L, "CAP_VIOLATION", "CRITICAL", "C001", "1200% 한도 초과", "NEW",
                        OffsetDateTime.parse("2026-07-10T09:00:00+09:00")));
        List<RecentValidationRunRow> runs = List.of(
                new RecentValidationRunRow(100L, month, 1, "MONTHLY", "COMPLETED", 8, "gaadmin",
                        OffsetDateTime.parse("2026-07-01T01:00:00+09:00"),
                        OffsetDateTime.parse("2026-07-01T01:05:00+09:00"),
                        null, null, null));
        return new DashboardSummaryResult(month, kpis, exceptions, runs);
    }

    @Test
    void summaryReturnsKpisAndRecentListsForGivenMonth() throws Exception {
        LocalDate month = LocalDate.of(2026, 7, 1);
        given(dashboardService.summarize(month)).willReturn(sampleResult(month));

        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .param("month", "2026-07")
                        .with(user("gaadmin").roles("GA_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.month").value("2026-07-01"))
                .andExpect(jsonPath("$.data.capViolation").value(1))
                .andExpect(jsonPath("$.data.capWarning").value(2))
                .andExpect(jsonPath("$.data.arbitrageCandidate").value(3))
                .andExpect(jsonPath("$.data.reconMismatch").value(4))
                .andExpect(jsonPath("$.data.journalImbalance").value(5))
                .andExpect(jsonPath("$.data.openException").value(6))
                .andExpect(jsonPath("$.data.recentExceptions[0].exceptionCaseId").value(10))
                .andExpect(jsonPath("$.data.recentExceptions[0].contractNo").value("C001"))
                .andExpect(jsonPath("$.data.recentRuns[0].validationRunId").value(100))
                .andExpect(jsonPath("$.data.recentRuns[0].currentStep").value(8));
    }

    @Test
    void summaryReturns400WhenMonthFormatIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .param("month", "2026-13")
                        .with(user("gaadmin").roles("GA_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));

        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .param("month", "hello")
                        .with(user("gaadmin").roles("GA_ADMIN")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("FGC-COMMON-002"));
    }

    @Test
    void summaryUsesCurrentMonthWhenMonthParameterIsOmitted() throws Exception {
        LocalDate currentMonth = YearMonth.now().atDay(1);
        given(dashboardService.summarize(currentMonth)).willReturn(sampleResult(currentMonth));

        mockMvc.perform(get("/api/v1/dashboard/summary")
                        .with(user("gaadmin").roles("GA_ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.month").value(currentMonth.toString()));

        verify(dashboardService).summarize(currentMonth);
    }

    @Test
    void summaryRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/v1/dashboard/summary").param("month", "2026-07"))
                .andExpect(status().isUnauthorized());
    }
}
