package com.susukkang.fgc.dashboard.dto;

/**
 * FGC-UI-DASH-W01 KPI 카드 6장의 건수
 */
public record DashboardKpiCounts(
        long capViolation,
        long capWarning,
        long arbitrageCandidate,
        long reconciliationMismatch,
        long journalImbalance,
        long openException
) {
}
