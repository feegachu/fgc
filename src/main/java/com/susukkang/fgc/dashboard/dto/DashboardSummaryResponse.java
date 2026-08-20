package com.susukkang.fgc.dashboard.dto;

import java.time.LocalDate;
import java.util.List;

/**
 * IF-API-03(FGC-UI-DASH-W01) 응답
 * KPI 6개를 "kpis" 객체로 감싸지 않고 최상위에 바로 둠
 *
 * 명세 필드명이 내부 도메인 타입과 다른 것 2개를 주의:
 *   reconMismatch (명세) = DashboardKpiCounts.reconciliationMismatch() (내부)
 *   recentRuns    (명세) = DashboardSummaryResult.recentValidationRuns() (내부)
 * 나머지 4개 KPI와 recentExceptions는 이름이 그대로 동일
 */
public record DashboardSummaryResponse(
        LocalDate month,
        long capViolation,
        long capWarning,
        long arbitrageCandidate,
        long reconMismatch,
        long journalImbalance,
        long openException,
        List<RecentExceptionResponse> recentExceptions,
        List<RecentValidationRunResponse> recentRuns
) {
    public static DashboardSummaryResponse from(DashboardSummaryResult result) {
        DashboardKpiCounts kpis = result.kpis();
        return new DashboardSummaryResponse(
                result.month(),
                kpis.capViolation(),
                kpis.capWarning(),
                kpis.arbitrageCandidate(),
                kpis.reconciliationMismatch(),
                kpis.journalImbalance(),
                kpis.openException(),
                result.recentExceptions().stream().map(RecentExceptionResponse::from).toList(),
                result.recentValidationRuns().stream().map(RecentValidationRunResponse::from).toList()
        );
    }
}
