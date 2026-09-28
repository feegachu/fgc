package com.susukkang.fgc.dashboard.service;

import com.susukkang.fgc.dashboard.dto.DashboardKpiCounts;
import com.susukkang.fgc.dashboard.dto.DashboardSummaryResult;
import com.susukkang.fgc.dashboard.dto.RecentExceptionRow;
import com.susukkang.fgc.dashboard.dto.RecentValidationRunRow;
import com.susukkang.fgc.dashboard.repository.DashboardQueryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

/**
 * FGC-UI-DASH-W01(FUN-057) 대시보드 요약 집계.
 *
 * DashboardQueryRepository의 집계와 최근 목록을 DashboardSummaryResult로 조립한다.
 */
@Service
@RequiredArgsConstructor
public class DashboardServiceImpl implements DashboardService {

    // 화면정의서(DASH-W01) 고정값: 최근 예외 5건, 최근 통합검증 실행 3건
    private static final int RECENT_EXCEPTION_LIMIT = 5;
    private static final int RECENT_VALIDATION_RUN_LIMIT = 3;

    private final DashboardQueryRepository dashboardQueryRepository;

    @Override
    @Transactional(readOnly = true)
    public DashboardSummaryResult summarize(LocalDate month) {
        // 카드 6장 + 최근 목록 2개를 하나의 읽기 전용 트랜잭션에서 조회
        long capViolation = dashboardQueryRepository.countCapViolation(month);
        long capWarning = dashboardQueryRepository.countCapWarning(month);
        long arbitrageCandidate = dashboardQueryRepository.countArbitrageCandidate(month);
        long reconciliationMismatch = dashboardQueryRepository.countReconciliationMismatch(month);
        long journalImbalance = dashboardQueryRepository.countJournalImbalance();
        long openException = dashboardQueryRepository.countOpenException();

        DashboardKpiCounts kpis = new DashboardKpiCounts(capViolation, capWarning, arbitrageCandidate, reconciliationMismatch,journalImbalance, openException);

        List<RecentExceptionRow> recentExceptions = dashboardQueryRepository.findRecentExceptions(RECENT_EXCEPTION_LIMIT);
        List<RecentValidationRunRow> recentValidationRuns = dashboardQueryRepository.findRecentValidationRuns(RECENT_VALIDATION_RUN_LIMIT);

        return new DashboardSummaryResult(month, kpis, recentExceptions, recentValidationRuns);
    }
}
