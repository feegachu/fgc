package com.susukkang.fgc.dashboard;

import com.susukkang.fgc.dashboard.dto.DashboardSummaryResult;
import com.susukkang.fgc.dashboard.dto.RecentExceptionRow;
import com.susukkang.fgc.dashboard.dto.RecentValidationRunRow;
import com.susukkang.fgc.dashboard.service.DashboardService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FGC-UI-DASH-W01(FUN-057) 요약 집계 통합테스트
 * 로컬 db에 행을 넣어 대시보드 지표 계약의 규칙(월 필터 유무, 최신 판정만 집계, MATCHED 제외)을 SQL 레벨에서 검증
 */
@SpringBootTest
@Transactional
class DashboardServiceIntegrationTest {

    @Autowired
    private DashboardService dashboardService;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long contractId(String contractNo) {
        return jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract WHERE contract_no = ?",
                Long.class, contractNo);
    }

    private Long capRuleSetId(String paymentStage) {
        return jdbcTemplate.queryForObject(
                "SELECT cap_rule_set_id FROM fgc.cap_rule_set WHERE payment_stage = ?",
                Long.class, paymentStage);
    }

    // 화면·계약 검증 목적이라 base_premium_amount 등 금액은 임의값이면 충분
    private void insertCapCheck(Long contractId, String paymentStage, LocalDate asOfDate,
                                 String resultStatus, OffsetDateTime checkedAt) {
        jdbcTemplate.update("""
                INSERT INTO fgc.cap_check
                    (contract_id, payment_stage, cap_rule_set_id, check_kind, as_of_date,
                     base_premium_amount, refund_12m_amount, compliance_deduction_amount,
                     limit_amount, included_amount, remaining_amount, usage_pct, result_status, checked_at)
                VALUES (?, ?, ?, 'REALTIME', ?, 100000, 0, 0, 1200000, 0, 1200000, 0, ?, ?)
                """, contractId, paymentStage, capRuleSetId(paymentStage), asOfDate, resultStatus, checkedAt);
    }

    private Long insertValidationRun(LocalDate month, int runNo, String status, OffsetDateTime createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, status, created_at)
                VALUES (?, ?, ?, ?)
                RETURNING validation_run_id
                """, Long.class, month, runNo, status, createdAt);
    }

    private void insertArbitrageCheck(Long validationRunId, Long contractId, LocalDate asOfDate, String resultStatus) {
        jdbcTemplate.update("""
                INSERT INTO fgc.arbitrage_check
                    (validation_run_id, contract_id, as_of_date, contract_month_no,
                     cumulative_paid_premium, net_difference_amount, standard_deduction_80_yn, result_status)
                VALUES (?, ?, ?, 1, 100000, 0, false, ?)
                """, validationRunId, contractId, asOfDate, resultStatus);
    }

    // uq_reconciliation_run은 (settlement_month, payment_stage, insurer_id, validation_run_id)로
    // 유일해야 해서, 같은 정산월·지급단계로 "재실행"을 흉내내려면 매번 다른 validation_run_id가 필요
    private Long insertReconciliationRun(LocalDate settlementMonth, String paymentStage,
                                          Long validationRunId, OffsetDateTime createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (settlement_month, payment_stage, validation_run_id, created_at)
                VALUES (?, ?, ?, ?)
                RETURNING reconciliation_run_id
                """, Long.class, settlementMonth, paymentStage, validationRunId, createdAt);
    }

    private void insertReconciliationResult(Long reconciliationRunId, String matchGroupKey, String resultType) {
        jdbcTemplate.update("""
                INSERT INTO fgc.reconciliation_result (reconciliation_run_id, match_group_key, result_type)
                VALUES (?, ?, ?)
                """, reconciliationRunId, matchGroupKey, resultType);
    }

    private void insertExceptionCase(String exceptionKey, String status, OffsetDateTime createdAt) {
        jdbcTemplate.update("""
                INSERT INTO fgc.exception_case
                    (exception_key, exception_type, severity, status,
                     source_entity_type, source_entity_id, title, created_at)
                VALUES (?, 'DATA_QUALITY', 'INFO', ?, 'TEST', ?, 'dashboard test', ?)
                """, exceptionKey, status, exceptionKey, createdAt);
    }

    // 차대 불균형 분개 하나(대변 없이 차변만)
    private void insertImbalancedJournal(String journalNo) {
        Long accountId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_account (account_code, account_name, normal_balance)
                VALUES (?, '테스트계정', 'DEBIT')
                RETURNING journal_account_id
                """, Long.class, journalNo + "-ACC");
        Long headerId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header (journal_no, journal_date, journal_type, source_entity_type, source_entity_id)
                VALUES (?, CURRENT_DATE, 'ADJUSTMENT', 'TEST', ?)
                RETURNING journal_header_id
                """, Long.class, journalNo, journalNo);
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (journal_header_id, line_no, journal_account_id, debit_amount)
                VALUES (?, 1, ?, 1000)
                """, headerId, accountId);
    }

    // 1. 1,200% 위반·주의: 계약·지급단계별 최신 판정만, 월 필터 있음
    @Test
    void summarizeCountsOnlyLatestCapCheckPerContract() {
        Long id = contractId("FGC-FGL01-202607-0001");
        // 같은 계약·지급단계에 WARNING(오전) → VIOLATION(오후) 순으로 재판정됨. 최신(VIOLATION)만 잡혀야 한다.
        insertCapCheck(id, "GA_TO_FC", LocalDate.of(2026, 7, 10), "WARNING",
                OffsetDateTime.parse("2026-07-10T09:00:00+09:00"));
        insertCapCheck(id, "GA_TO_FC", LocalDate.of(2026, 7, 10), "VIOLATION",
                OffsetDateTime.parse("2026-07-10T15:00:00+09:00"));

        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2026, 7, 1));

        assertThat(result.kpis().capViolation()).isEqualTo(1);
        assertThat(result.kpis().capWarning()).isEqualTo(0);
    }

    @Test
    void summarizeExcludesCapCheckOutsideRequestedMonth() {
        Long id = contractId("FGC-FGL01-202607-0002");
        // 이 계약의 "최신" 판정 자체가 6월 것이면, 7월 대시보드에는 안 잡혀야 한다.
        insertCapCheck(id, "GA_TO_FC", LocalDate.of(2026, 6, 20), "VIOLATION",
                OffsetDateTime.parse("2026-06-20T09:00:00+09:00"));

        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2026, 7, 1));

        assertThat(result.kpis().capViolation()).isEqualTo(0);
    }

    // 2. 차익거래 검토대상: 월 필터 없음, dedup 없음(전부 카운트)
    @Test
    void summarizeCountsAllArbitrageCandidatesAcrossMonthsWithoutDedup() {
        Long id = contractId("FGC-FGL01-202607-0003");
        Long run1 = insertValidationRun(LocalDate.of(2026, 6, 1), 1, "COMPLETED",
                OffsetDateTime.parse("2026-06-30T09:00:00+09:00"));
        Long run2 = insertValidationRun(LocalDate.of(2026, 7, 1), 1, "COMPLETED",
                OffsetDateTime.parse("2026-07-31T09:00:00+09:00"));
        // 같은 계약이라도 dedup 없이 둘 다, 월도 다르지만 둘 다 잡혀야 한다(월 필터 없음).
        insertArbitrageCheck(run1, id, LocalDate.of(2026, 6, 30), "CANDIDATE");
        insertArbitrageCheck(run2, id, LocalDate.of(2026, 7, 31), "CANDIDATE");

        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2026, 7, 1));

        assertThat(result.kpis().arbitrageCandidate()).isEqualTo(2);
    }

    // 3. 대사 불일치: 정산월·지급단계·보험사별 최신 run만, MATCHED 제외
    @Test
    void summarizeCountsReconciliationMismatchFromLatestRunOnlyAndExcludesMatched() {
        LocalDate settlementMonth = LocalDate.of(2026, 7, 1);
        // 같은 정산월·지급단계에 재실행된 run 두 개. 이전 run의 불일치는 세면 안 되고,
        // 최신 run에서도 MATCHED는 제외해야 한다.
        Long oldValidationRun = insertValidationRun(settlementMonth, 1, "COMPLETED",
                OffsetDateTime.parse("2026-08-01T08:00:00+09:00"));
        Long oldRun = insertReconciliationRun(settlementMonth, "GA_TO_FC", oldValidationRun,
                OffsetDateTime.parse("2026-08-01T09:00:00+09:00"));
        insertReconciliationResult(oldRun, "GROUP-OLD-1", "AMOUNT_DIFFERENCE");

        Long latestValidationRun = insertValidationRun(settlementMonth, 2, "COMPLETED",
                OffsetDateTime.parse("2026-08-01T14:00:00+09:00"));
        Long latestRun = insertReconciliationRun(settlementMonth, "GA_TO_FC", latestValidationRun,
                OffsetDateTime.parse("2026-08-01T15:00:00+09:00"));
        insertReconciliationResult(latestRun, "GROUP-NEW-1", "AMOUNT_DIFFERENCE");
        insertReconciliationResult(latestRun, "GROUP-NEW-2", "MATCHED");

        DashboardSummaryResult result = dashboardService.summarize(settlementMonth);

        // 이전 run의 1건은 제외, 최신 run의 MATCHED 1건도 제외 → 1건만 남는다
        assertThat(result.kpis().reconciliationMismatch()).isEqualTo(1);
    }

    // 4. 원장 불균형: vw_journal_imbalance 그대로
    @Test
    void summarizeCountsJournalImbalance() {
        insertImbalancedJournal("DASH-TEST-JN-1");

        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2026, 7, 1));

        assertThat(result.kpis().journalImbalance()).isEqualTo(1);
    }

    // 5. 미처리 예외: 월 필터 없음, NEW+IN_REVIEW만
    @Test
    void summarizeCountsOpenExceptionsRegardlessOfMonth() {
        OffsetDateTime old = OffsetDateTime.parse("2026-01-01T09:00:00+09:00");
        insertExceptionCase("DASH-TEST-EXC-NEW", "NEW", old);
        insertExceptionCase("DASH-TEST-EXC-REVIEW", "IN_REVIEW", old);
        insertExceptionCase("DASH-TEST-EXC-RESOLVED", "RESOLVED", old);

        // 예외 발생월(1월)과 무관하게 조회월(7월) 기준으로도 NEW/IN_REVIEW 2건이 그대로 잡혀야 한다
        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2026, 7, 1));

        assertThat(result.kpis().openException()).isEqualTo(2);
    }

    // 6. 빈 데이터는 0
    @Test
    void summarizeReturnsZeroCountsAndEmptyListsWhenNoDataExists() {
        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2099, 1, 1));

        assertThat(result.kpis().capViolation()).isEqualTo(0);
        assertThat(result.kpis().capWarning()).isEqualTo(0);
        assertThat(result.kpis().arbitrageCandidate()).isEqualTo(0);
        assertThat(result.kpis().reconciliationMismatch()).isEqualTo(0);
        assertThat(result.kpis().journalImbalance()).isEqualTo(0);
        assertThat(result.kpis().openException()).isEqualTo(0);
    }

    // 7. 최근 예외 5건: 최신순, 5건 제한
    @Test
    void summarizeReturnsAtMostFiveRecentExceptionsSortedByLatest() {
        for (int i = 1; i <= 6; i++) {
            insertExceptionCase("DASH-TEST-RECENT-EXC-" + i, "NEW",
                    OffsetDateTime.parse("2026-07-0" + i + "T09:00:00+09:00"));
        }

        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2026, 7, 1));
        List<RecentExceptionRow> recent = result.recentExceptions();

        assertThat(recent).hasSize(5);
        // 가장 최근(7월 6일)이 맨 앞
        assertThat(recent.get(0).createdAt()).isEqualTo(OffsetDateTime.parse("2026-07-06T09:00:00+09:00"));
    }

    // 8. 최근 검증 실행 3건: 최신순, 3건 제한, 진행률(%) 필드 없음
    @Test
    void summarizeReturnsAtMostThreeRecentValidationRunsSortedByLatest() {
        insertValidationRun(LocalDate.of(2026, 4, 1), 1, "COMPLETED", OffsetDateTime.parse("2026-05-01T09:00:00+09:00"));
        insertValidationRun(LocalDate.of(2026, 5, 1), 1, "COMPLETED", OffsetDateTime.parse("2026-06-01T09:00:00+09:00"));
        insertValidationRun(LocalDate.of(2026, 6, 1), 1, "COMPLETED", OffsetDateTime.parse("2026-07-01T09:00:00+09:00"));
        insertValidationRun(LocalDate.of(2026, 7, 1), 1, "COMPLETED", OffsetDateTime.parse("2026-08-01T09:00:00+09:00"));

        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2026, 7, 1));
        List<RecentValidationRunRow> recent = result.recentValidationRuns();

        assertThat(recent).hasSize(3);
        assertThat(recent.get(0).validationMonth()).isEqualTo(LocalDate.of(2026, 7, 1));
    }
}
