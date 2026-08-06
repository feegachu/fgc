package com.susukkang.fgc.dashboard.service;

import com.susukkang.fgc.dashboard.dto.DashboardKpiCounts;
import com.susukkang.fgc.dashboard.dto.DashboardSummaryResult;
import com.susukkang.fgc.dashboard.dto.RecentExceptionRow;
import com.susukkang.fgc.dashboard.dto.RecentValidationRunRow;
import com.susukkang.fgc.dashboard.mapper.DashboardMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * FGC-UI-DASH-W01(FUN-057) 대시보드 요약 집계.
 *
 * DashboardMapper의 count 계열/find 계열 결과를 그대로 모아 DashboardSummaryResult로 조립
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    // 화면정의서(DASH-W01) 고정값: 최근 예외 5건, 최근 통합검증 실행 3건
    private static final int RECENT_EXCEPTION_LIMIT = 5;
    private static final int RECENT_VALIDATION_RUN_LIMIT = 3;

    private final DashboardMapper dashboardMapper;

    @Override
    @Transactional(readOnly = true)
    public DashboardSummaryResult summarize(LocalDate month) {
        // 카드 6장 + 최근 목록 2개를 하나의 읽기 전용 트랜잭션에서 조회
        long capVilation = dashboardMapper.countCapViolation(month);
        long capWarning = dashboardMapper.countCapWarning(month);
        long arbitrageCandidate = dashboardMapper.countArbitrageCandidate();
        long reconciliationMismatch = dashboardMapper.countReconciliationMismatch(month);
        long journalImbalance = dashboardMapper.countJournalImbalance();
        long openException = dashboardMapper.countOpenException();

        DashboardKpiCounts kpis = new DashboardKpiCounts(capVilation, capWarning, arbitrageCandidate, reconciliationMismatch,journalImbalance, openException);

        List<RecentExceptionRow> recentExceptions = dashboardMapper.findRecentExceptions(RECENT_EXCEPTION_LIMIT);
        List<RecentValidationRunRow> recentValidationRuns = dashboardMapper.findRecentValidationRuns(RECENT_VALIDATION_RUN_LIMIT);

        DashboardSummaryResult dashboardSummaryResult = new DashboardSummaryResult(month, kpis, recentExceptions, recentValidationRuns);

        return dashboardSummaryResult;
    }
}
