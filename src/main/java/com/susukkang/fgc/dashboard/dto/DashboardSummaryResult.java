package com.susukkang.fgc.dashboard.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * DashboardService.summarize()의 조립 결과
 * 대시보드 요약 API가 이 결과를 ApiResponse용 응답 DTO로 변환해서 내려줌
 */
public record DashboardSummaryResult(
        LocalDate month,
        DashboardKpiCounts kpis,
        List<RecentExceptionRow> recentExceptions,
        List<RecentValidationRunRow> recentValidationRuns
) {
}
