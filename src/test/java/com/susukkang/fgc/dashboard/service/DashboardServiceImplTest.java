package com.susukkang.fgc.dashboard.service;

import com.susukkang.fgc.dashboard.dto.DashboardSummaryResult;
import com.susukkang.fgc.dashboard.dto.RecentExceptionRow;
import com.susukkang.fgc.dashboard.dto.RecentValidationRunRow;
import com.susukkang.fgc.dashboard.mapper.DashboardMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * DashboardServiceImpl 단위테스트
 * DashboardMapper를 mock으로 대체해 "매퍼 결과를 어떻게 조립하는지"만 검증
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {

    @Mock
    private DashboardMapper dashboardMapper;

    private DashboardServiceImpl dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardServiceImpl(dashboardMapper);
    }

    @Test
    void summarizeAssemblesKpiCountsInDeclaredFieldOrder() {
        LocalDate month = LocalDate.of(2026, 7, 1);
        // 6개 값을 서로 다르게 줘서, 조립 순서가 하나라도 틀리면 바로 드러나게 함
        when(dashboardMapper.countCapViolation(month)).thenReturn(1L);
        when(dashboardMapper.countCapWarning(month)).thenReturn(2L);
        when(dashboardMapper.countArbitrageCandidate()).thenReturn(3L);
        when(dashboardMapper.countReconciliationMismatch(month)).thenReturn(4L);
        when(dashboardMapper.countJournalImbalance()).thenReturn(5L);
        when(dashboardMapper.countOpenException()).thenReturn(6L);
        when(dashboardMapper.findRecentExceptions(5)).thenReturn(List.of());
        when(dashboardMapper.findRecentValidationRuns(3)).thenReturn(List.of());

        DashboardSummaryResult result = dashboardService.summarize(month);

        assertThat(result.month()).isEqualTo(month);
        assertThat(result.kpis().capViolation()).isEqualTo(1L);
        assertThat(result.kpis().capWarning()).isEqualTo(2L);
        assertThat(result.kpis().arbitrageCandidate()).isEqualTo(3L);
        assertThat(result.kpis().reconciliationMismatch()).isEqualTo(4L);
        assertThat(result.kpis().journalImbalance()).isEqualTo(5L);
        assertThat(result.kpis().openException()).isEqualTo(6L);
        assertThat(result.recentExceptions()).isEmpty();
        assertThat(result.recentValidationRuns()).isEmpty();
    }

    // arbitrageCandidate/journalImbalance/openException은 월과 무관해야함
    @Test
    void summarizeCallsMonthIndependentCountsWithoutMonthParameter() {
        LocalDate month = LocalDate.of(2026, 7, 1);
        when(dashboardMapper.findRecentExceptions(5)).thenReturn(List.of());
        when(dashboardMapper.findRecentValidationRuns(3)).thenReturn(List.of());

        dashboardService.summarize(month);

        org.mockito.Mockito.verify(dashboardMapper).countArbitrageCandidate();
        org.mockito.Mockito.verify(dashboardMapper).countJournalImbalance();
        org.mockito.Mockito.verify(dashboardMapper).countOpenException();
        org.mockito.Mockito.verify(dashboardMapper).countCapViolation(month);
        org.mockito.Mockito.verify(dashboardMapper).countCapWarning(month);
        org.mockito.Mockito.verify(dashboardMapper).countReconciliationMismatch(month);
    }

    @Test
    void summarizeUsesFixedLimitsForRecentListsFromScreenSpec() {
        LocalDate month = LocalDate.of(2026, 7, 1);
        List<RecentExceptionRow> exceptions = List.of(
                new RecentExceptionRow(1L, "CAP_VIOLATION", "CRITICAL", "C001", "제목", "NEW",
                        OffsetDateTime.parse("2026-07-10T09:00:00+09:00")));
        List<RecentValidationRunRow> runs = List.of(
                new RecentValidationRunRow(1L, month, 1, "MONTHLY", "COMPLETED", 8, "gaadmin",
                        null, null, null, null, null));
        when(dashboardMapper.findRecentExceptions(5)).thenReturn(exceptions);
        when(dashboardMapper.findRecentValidationRuns(3)).thenReturn(runs);

        DashboardSummaryResult result = dashboardService.summarize(month);

        assertThat(result.recentExceptions()).isEqualTo(exceptions);
        assertThat(result.recentValidationRuns()).isEqualTo(runs);
    }
}
