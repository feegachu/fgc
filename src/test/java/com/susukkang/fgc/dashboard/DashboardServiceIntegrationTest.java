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

    // guard_run_lifecycle(V7)이 INSERT 시 status='CREATED'만 허용한다 — 곧바로 COMPLETED로
    // INSERT할 수 없고 CREATED→RUNNING→COMPLETED 순서로 UPDATE해야 한다. ck_validation_run_step은
    // COMPLETED일 때 current_step이 정확히 8이어야 한다고 강제한다.
    private Long insertValidationRun(LocalDate month, int runNo, String status, OffsetDateTime createdAt) {

        return insertValidationRun(month, runNo, status, createdAt, "MONTHLY");
    }

    private Long insertValidationRun(LocalDate month, int runNo, String status, OffsetDateTime createdAt, String runType) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status, created_at)
                VALUES (?, ?, ?, 'CREATED', ?)
                RETURNING validation_run_id
                """, Long.class, month, runNo, runType, createdAt);

        if (!"CREATED".equals(status)) {
            jdbcTemplate.update("UPDATE fgc.validation_run SET status='RUNNING' WHERE validation_run_id=?", id);
            if ("COMPLETED".equals(status)) {
                jdbcTemplate.update(
                        "UPDATE fgc.validation_run SET status='COMPLETED', current_step=8 WHERE validation_run_id=?", id);
            } else if ("FAILED".equals(status)) {
                jdbcTemplate.update("UPDATE fgc.validation_run SET status='FAILED' WHERE validation_run_id=?", id);
            }
        }
        return id;
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

    private Long insertExceptionCase(String exceptionKey, String status, OffsetDateTime createdAt) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.exception_case
                    (exception_key, exception_type, severity, status,
                     source_entity_type, source_entity_id, title, created_at)
                VALUES (?, 'DATA_QUALITY', 'INFO', ?, 'TEST', ?, 'dashboard test', ?)

                RETURNING exception_case_id
                """, Long.class, exceptionKey, status, exceptionKey, createdAt);

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
        // 이 계약의 유일한 판정이 6월 것이면, 7월 대시보드에는 안 잡혀야 한다.
        insertCapCheck(id, "GA_TO_FC", LocalDate.of(2026, 6, 20), "VIOLATION",
                OffsetDateTime.parse("2026-06-20T09:00:00+09:00"));

        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2026, 7, 1));

        assertThat(result.kpis().capViolation()).isEqualTo(0);
    }

    // coderabbitai 지적: vw_latest_cap_check는 계약·지급단계별 "전체 기간" 최신 판정을 먼저 고르므로,
    // 7월 VIOLATION 이후 8월에 재판정(NORMAL)되면 그 계약의 전체 기간 최신은 8월 NORMAL이 되어
    // 7월 대시보드에서 VIOLATION이 통째로 사라진다. 월 필터를 먼저 적용해야 이 문제가 없다.
    @Test
    void summarizeStillCountsJulyViolationEvenWhenContractWasRecheckedNormalInAugust() {
        Long id = contractId("FGC-FGL01-202607-0004");
        insertCapCheck(id, "GA_TO_FC", LocalDate.of(2026, 7, 15), "VIOLATION",
                OffsetDateTime.parse("2026-07-15T09:00:00+09:00"));
        insertCapCheck(id, "GA_TO_FC", LocalDate.of(2026, 8, 5), "NORMAL",
                OffsetDateTime.parse("2026-08-05T09:00:00+09:00"));

        DashboardSummaryResult julyResult = dashboardService.summarize(LocalDate.of(2026, 7, 1));
        DashboardSummaryResult augustResult = dashboardService.summarize(LocalDate.of(2026, 8, 1));

        assertThat(julyResult.kpis().capViolation()).isEqualTo(1);
        assertThat(augustResult.kpis().capViolation()).isEqualTo(0);
    }

    // 2. 차익거래 검토대상: 이번 달만, 계약·지급단계별 최신 1건만(#257)
    @Test
    void summarizeCountsArbitrageCandidatesInMonthAndCollapsesReruns() {
        Long id = contractId("FGC-FGL01-202607-0003");
        Long juneRun = insertValidationRun(LocalDate.of(2026, 6, 1), 1, "COMPLETED",
                OffsetDateTime.parse("2026-06-30T09:00:00+09:00"));
        Long julyRun1 = insertValidationRun(LocalDate.of(2026, 7, 1), 1, "COMPLETED",
                OffsetDateTime.parse("2026-07-31T09:00:00+09:00"));
        Long julyRun2 = insertValidationRun(LocalDate.of(2026, 7, 1), 2, "COMPLETED",
                OffsetDateTime.parse("2026-08-01T09:00:00+09:00"));
        // 6월 판정은 7월 카드에 들어오면 안 된다(기준월 변경 시 카드를 다시 계산한다).
        insertArbitrageCheck(juneRun, id, LocalDate.of(2026, 6, 30), "CANDIDATE");
        // 7월을 두 번 돌렸다 — uq_arbitrage_check에 validation_run_id가 있어 행은 둘이지만
        // 같은 계약·지급단계라 카드에는 1건으로 보여야 한다(예외함 카드와 같은 단위).
        insertArbitrageCheck(julyRun1, id, LocalDate.of(2026, 7, 31), "CANDIDATE");
        insertArbitrageCheck(julyRun2, id, LocalDate.of(2026, 7, 31), "CANDIDATE");

        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2026, 7, 1));

        assertThat(result.kpis().arbitrageCandidate()).isEqualTo(1);
    }

    // 재실행에서 판정이 뒤집히면 카드는 최신 판정을 따라야 한다 — 접을 때 옛 행을
    // 고르면 이미 해소된 검토대상이 카드에 남는다.
    @Test
    void summarizeUsesLatestRunResultWhenRerunClearsCandidate() {
        Long id = contractId("FGC-FGL01-202607-0004");
        Long julyRun1 = insertValidationRun(LocalDate.of(2026, 7, 1), 3, "COMPLETED",
                OffsetDateTime.parse("2026-07-31T09:00:00+09:00"));
        Long julyRun2 = insertValidationRun(LocalDate.of(2026, 7, 1), 4, "COMPLETED",
                OffsetDateTime.parse("2026-08-01T09:00:00+09:00"));
        insertArbitrageCheck(julyRun1, id, LocalDate.of(2026, 7, 31), "CANDIDATE");
        insertArbitrageCheck(julyRun2, id, LocalDate.of(2026, 7, 31), "CLEAR");

        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2026, 7, 1));

        assertThat(result.kpis().arbitrageCandidate()).isEqualTo(0);
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

    // 6. 월 필터가 있는 KPI(1,200%·차익거래·대사불일치)는 미래월(2099-01)에 0이어야 한다.
    // journalImbalance/openException/recentExceptions/recentValidationRuns는
    // 설계상 월 필터가 없어(각 Mapper 주석 참고 — "월 필터: 없음") 로컬 dev DB에 이미
    // 존재하는 시드 데이터를 그대로 반영한다 — 그래서
    // journalImbalance는 이 테스트에서 단언하지 않는다(월과 무관하게 vw_journal_imbalance
    // 전체를 세므로, "미래월이라 0"이라는 근거가 없다. 실제로 0건인지는 4번
    // summarizeCountsJournalImbalance()가 별도로 검증한다). 리스트도 "비어 있다"를
    // 단언하는 대신 "널이 아닌 리스트를 돌려준다"(예외 없이 항상 채워진 배열 필드다)는
    // 계약만 확인한다 — findRecentExceptions/findRecentValidationRuns가 실제로 빈
    // 리스트를 돌려주는지는 DashboardServiceImplTest에서 Mapper를 빈 리스트로 스텁해
    // 별도로 검증한다.
    @Test
    void summarizeReturnsZeroCountsForMonthFilteredKpisAndNeverReturnsNullLists() {
        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2099, 1, 1));

        assertThat(result.kpis().capViolation()).isEqualTo(0);
        assertThat(result.kpis().capWarning()).isEqualTo(0);
        assertThat(result.kpis().arbitrageCandidate()).isEqualTo(0);
        assertThat(result.kpis().reconciliationMismatch()).isEqualTo(0);
        assertThat(result.recentExceptions()).isNotNull();
        assertThat(result.recentValidationRuns()).isNotNull();
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


    // created_at만으로 정렬하면 동시각(tie) 행의 순서가 보장되지 않아
    // LIMIT 경계에서 요청마다 다른 5건이 나올 수 있다. exception_case_id를 보조 정렬 키로 둬서
    // 결정적으로 만든다 — 동일 시각 6건 중 최신 id 5개가 항상 같은 순서로 나와야 한다.
    @Test
    void summarizeBreaksRecentExceptionTiesByIdWhenCreatedAtIsIdentical() {
        OffsetDateTime sameInstant = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            ids.add(insertExceptionCase("DASH-TEST-TIE-EXC-" + i, "NEW", sameInstant));
        }

        DashboardSummaryResult result = dashboardService.summarize(LocalDate.of(2026, 7, 1));
        List<RecentExceptionRow> recent = result.recentExceptions();

        assertThat(recent).hasSize(5);
        List<Long> expectedIdsDesc = ids.stream()
                .sorted(java.util.Comparator.reverseOrder())
                .limit(5)
                .toList();
        assertThat(recent.stream().map(RecentExceptionRow::exceptionCaseId).toList())
                .isEqualTo(expectedIdsDesc);
    }

    // 8. 최근 검증 실행 3건: 생성 시각(created_at) 최신순, 3건 제한, current_step(진행률) 포함
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
        assertThat(recent.get(0).currentStep()).isEqualTo(8);
    }

    // coderabbitai 지적: validation_month DESC로 정렬하면 "과거 기준월을 나중에 재실행"한 경우
    // 실제로 더 최근에 생성된 실행이 뒤로 밀린다. created_at 기준으로 정렬해야 한다.
    @Test
    void summarizeOrdersRecentRunsByCreationTimeNotValidationMonthWhenRerunOutOfOrder() {
        // 5월 실행이 6월 실행보다 나중(오늘) 재실행됐다 — 기준월 순서와 생성 순서가 뒤바뀐 경우
        Long juneRun = insertValidationRun(LocalDate.of(2026, 6, 1), 1, "COMPLETED",
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00"));
        Long mayRerun = insertValidationRun(LocalDate.of(2026, 5, 1), 2, "COMPLETED",
                OffsetDateTime.parse("2026-08-01T09:00:00+09:00"));

        List<RecentValidationRunRow> recent = dashboardService.summarize(LocalDate.of(2026, 8, 1))
                .recentValidationRuns();

        assertThat(recent.get(0).validationRunId()).isEqualTo(mayRerun);
        assertThat(recent.get(1).validationRunId()).isEqualTo(juneRun);
    }

    // DASH-W01은 "최근 월 통합검증 실행 3건"을 정의한다. 계약별 수동검증(MANUAL_CONTRACT)이
    // 섞여서 조회되면, 그 실행들이 최근 3건 자리를 차지해 정작 월 통합검증(MONTHLY) 기록이 화면에서
    // 사라질 수 있다. MONTHLY만 걸러서 최근 3건을 반환해야 한다.
    @Test
    void summarizeReturnsOnlyMonthlyRunsAmongRecentValidationRunsEvenWhenManualContractRunsAreNewer() {
        Long monthlyRun1 = insertValidationRun(LocalDate.of(2026, 4, 1), 1, "COMPLETED",
                OffsetDateTime.parse("2026-05-01T09:00:00+09:00"), "MONTHLY");
        Long monthlyRun2 = insertValidationRun(LocalDate.of(2026, 5, 1), 1, "COMPLETED",
                OffsetDateTime.parse("2026-06-01T09:00:00+09:00"), "MONTHLY");
        Long monthlyRun3 = insertValidationRun(LocalDate.of(2026, 6, 1), 1, "COMPLETED",
                OffsetDateTime.parse("2026-07-01T09:00:00+09:00"), "MONTHLY");
        // MANUAL_CONTRACT 실행들이 MONTHLY보다 나중에 생성됐다 — 정렬만 하면 최근 3건 자리를 차지한다.
        insertValidationRun(LocalDate.of(2026, 7, 1), 1, "COMPLETED",
                OffsetDateTime.parse("2026-08-01T09:00:00+09:00"), "MANUAL_CONTRACT");
        insertValidationRun(LocalDate.of(2026, 7, 1), 2, "COMPLETED",
                OffsetDateTime.parse("2026-08-02T09:00:00+09:00"), "MANUAL_CONTRACT");

        List<RecentValidationRunRow> recent = dashboardService.summarize(LocalDate.of(2026, 8, 1))
                .recentValidationRuns();

        assertThat(recent).hasSize(3);
        assertThat(recent).allSatisfy(row -> assertThat(row.runType()).isEqualTo("MONTHLY"));
        assertThat(recent.stream().map(RecentValidationRunRow::validationRunId).toList())
                .containsExactly(monthlyRun3, monthlyRun2, monthlyRun1);
    }
}
