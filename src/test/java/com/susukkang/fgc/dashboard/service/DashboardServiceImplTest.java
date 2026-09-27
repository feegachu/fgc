package com.susukkang.fgc.dashboard.service;

import com.susukkang.fgc.dashboard.dto.DashboardSummaryResult;
import com.susukkang.fgc.dashboard.dto.RecentExceptionRow;
import com.susukkang.fgc.dashboard.dto.RecentValidationRunRow;
import com.susukkang.fgc.dashboard.repository.DashboardQueryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * DashboardServiceImpl 단위테스트
 * DashboardQueryRepository를 mock으로 대체해 조회 결과 조립과 호출 조건을 검증
 */
@ExtendWith(MockitoExtension.class)
class DashboardServiceImplTest {

    @Mock
    private DashboardQueryRepository dashboardQueryRepository;

    private DashboardServiceImpl dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardServiceImpl(dashboardQueryRepository);
    }

    @Test
    void summarizeAssemblesKpiCountsInDeclaredFieldOrder() {
        LocalDate month = LocalDate.of(2026, 7, 1);
        // 6개 값을 서로 다르게 줘서, 조립 순서가 하나라도 틀리면 바로 드러나게 함
        when(dashboardQueryRepository.countCapViolation(month)).thenReturn(1L);
        when(dashboardQueryRepository.countCapWarning(month)).thenReturn(2L);
        when(dashboardQueryRepository.countArbitrageCandidate(month)).thenReturn(3L);
        when(dashboardQueryRepository.countReconciliationMismatch(month)).thenReturn(4L);
        when(dashboardQueryRepository.countJournalImbalance()).thenReturn(5L);
        when(dashboardQueryRepository.countOpenException()).thenReturn(6L);
        when(dashboardQueryRepository.findRecentExceptions(5)).thenReturn(List.of());
        when(dashboardQueryRepository.findRecentValidationRuns(3)).thenReturn(List.of());

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

    // journalImbalance/openException은 월과 무관해야함.
    // arbitrageCandidate는 화면정의서 DASH-W01 KPI 표가 "기준월 변경 → 6장 카드를 다시 계산"
    // 이라고 못박으므로 1,200%·대사불일치와 같이 기준월을 받아야 한다(#257).
    @Test
    void summarizeCallsMonthIndependentCountsWithoutMonthParameter() {
        LocalDate month = LocalDate.of(2026, 7, 1);
        when(dashboardQueryRepository.findRecentExceptions(5)).thenReturn(List.of());
        when(dashboardQueryRepository.findRecentValidationRuns(3)).thenReturn(List.of());

        dashboardService.summarize(month);

        verify(dashboardQueryRepository).countJournalImbalance();
        verify(dashboardQueryRepository).countOpenException();
        verify(dashboardQueryRepository).countCapViolation(month);
        verify(dashboardQueryRepository).countCapWarning(month);
        verify(dashboardQueryRepository).countArbitrageCandidate(month);
        verify(dashboardQueryRepository).countReconciliationMismatch(month);
        verify(dashboardQueryRepository).findRecentExceptions(5);
        verify(dashboardQueryRepository).findRecentValidationRuns(3);
        verifyNoMoreInteractions(dashboardQueryRepository);
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
        when(dashboardQueryRepository.findRecentExceptions(5)).thenReturn(exceptions);
        when(dashboardQueryRepository.findRecentValidationRuns(3)).thenReturn(runs);

        DashboardSummaryResult result = dashboardService.summarize(month);

        assertThat(result.recentExceptions()).isEqualTo(exceptions);
        assertThat(result.recentValidationRuns()).isEqualTo(runs);
    }
}
