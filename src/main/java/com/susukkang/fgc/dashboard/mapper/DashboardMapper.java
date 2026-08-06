package com.susukkang.fgc.dashboard.mapper;

import com.susukkang.fgc.dashboard.dto.RecentExceptionRow;
import com.susukkang.fgc.dashboard.dto.RecentValidationRunRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDate;
import java.util.List;

/**
 * FGC-UI-DASH-W01(FUN-057) 요약 집계 쿼리
 */
@Mapper
public interface DashboardMapper {

    /**
     * 1,200% 위반 건수
     * 원본: vw_latest_cap_check(계약·지급단계별 최신 판정만) WHERE result_status = 'VIOLATION'
     * 월 필터: 있음 — as_of_date가 month가 속한 달에 포함되는 행만
     */
    long countCapViolation(@Param("month") LocalDate month);

    /**
     * 1,200% 주의 건수
     * countCapViolation과 원본·월 필터 동일, result_status만 'WARNING'
     */
    long countCapWarning(@Param("month") LocalDate month);

    /**
     * 차익거래 검토대상 건수
     * 원본: arbitrage_check WHERE result_status = 'CANDIDATE'
     * 월 필터: 없음 — 존재하는 행 전부
     * 중복 처리: 없음 — 계약당 여러 번 재검증됐어도 dedup하지 않고 행 개수 그대로 카운트
     */
    long countArbitrageCandidate();

    /**
     * 대사 불일치 건수
     * 원본: reconciliation_result WHERE result_type <> 'MATCHED'
     * 월 필터: reconciliation_run.settlement_month가 month가 속한 달인 것만
     * 중복 처리: 정산월·지급단계·보험사별로 reconciliation_run이 여러 번(재실행) 있을 수 있음
     *   가장 최신 reconciliation_run 1개에 속한 reconciliation_result만 세고, 이전 run 결과는 제외
     */
    long countReconciliationMismatch(@Param("month") LocalDate month);

    /**
     * 원장 불균형 건수
     * 원본: vw_journal_imbalance 전체 행 수. 월 필터·dedup 없음
     */
    long countJournalImbalance();

    /**
     * 미처리 예외 건수
     * 원본: exception_case WHERE status IN ('NEW', 'IN_REVIEW')
     * 월 필터: 없음 — 지금 열려있는 것 전체
     */
    long countOpenException();

    /**
     * 최근 예외 목록
     * exception_case를 최신순(created_at DESC)으로 limit개
     * insurance_contract를 LEFT JOIN해서 contract_no를 함께 가져옴
     */
    List<RecentExceptionRow> findRecentExceptions(@Param("limit") int limit);

    /**
     * 최근 통합검증 실행 목록
     * validation_run을 최신순으로 limit개
     * triggered_by/finalized_by는 app_user.user_id FK라 화면에
     * 보여줄 login_id를 얻으려면 app_user를 LEFT JOIN
     */
    List<RecentValidationRunRow> findRecentValidationRuns(@Param("limit") int limit);
}
