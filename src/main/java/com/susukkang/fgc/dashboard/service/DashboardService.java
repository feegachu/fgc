package com.susukkang.fgc.dashboard.service;

import com.susukkang.fgc.dashboard.dto.DashboardSummaryResult;

import java.time.LocalDate;

/**
 * FGC-UI-DASH-W01(FUN-057) 요약 집계 오케스트레이션
 * 여러 도메인의 결과 테이블을 읽기 전용으로 집계해 조립
 */
public interface DashboardService {

    /**
     * 기준월 하나에 대한 대시보드 요약(KPI 6장 + 최근 예외 5건 + 최근 검증실행 3건)을 조립=
     *
     * @param month 기준월 — 그 달 1일로 받음
     */
    DashboardSummaryResult summarize(LocalDate month);
}
