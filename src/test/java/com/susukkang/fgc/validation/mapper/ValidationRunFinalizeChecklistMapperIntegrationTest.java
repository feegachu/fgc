package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.validation.dto.FinalizeChecklistCounts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 요구사항 : FGC-FUN-044 검증 결과 확정·잠금
 *
 * 설명 : IF-API-50 확정 체크리스트 PostgreSQL 통합 테스트
 *
 * @author yslee
 * @since 2026-08-19
 * @version 1.2
 */
@SpringBootTest
@Transactional
class ValidationRunFinalizeChecklistMapperIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2088, 1, 1);

    @Autowired
    private ValidationRunMapper validationRunMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 2026-08-23 yslee - #330 확정 게이트 예외 조건을 실행 범위 → 검증월 범위로 교정
    // 기존 코드: 조건 3·4가 validation_run_id 범위라 실시간 경로(run_id NULL)의
    //           CRITICAL·POLICY_* 미처리 예외가 있어도 6/6 통과로 FINALIZED 될 수 있었음
    // 개선: 같은 검증월이면 실행이 달라도, 실행이 없어도(실시간) 확정을 막는지 검증
    @Test
    void countsFailuresInTheDocumentedScopesIncludingRealtimeExceptions() {
        Long currentRunId = createCompletedRun(TEST_MONTH);
        Long otherRunId = createCompletedRun(TEST_MONTH);
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);

        insertJournalImbalance(currentRunId, contractId, "current");
        insertJournalImbalance(otherRunId, contractId, "other");

        insertException(currentRunId, TEST_MONTH, "CRITICAL", "OTHER", "NEW", "critical-open");
        insertException(currentRunId, TEST_MONTH, "CRITICAL", "OTHER", "RESOLVED", "critical-resolved");
        insertException(otherRunId, TEST_MONTH, "CRITICAL", "OTHER", "NEW", "critical-other-run");
        // #330 핵심 회귀: 실시간 확정 경로(FUN-034)는 validation_run_id 가 NULL 이다
        insertException(null, TEST_MONTH, "CRITICAL", "CAP_VIOLATION", "NEW", "critical-realtime");
        insertException(null, TEST_MONTH.plusMonths(1), "CRITICAL", "CAP_VIOLATION", "NEW",
                "critical-other-month");
        insertException(currentRunId, TEST_MONTH, "WARNING", "POLICY_MISSING", "IN_REVIEW", "policy-open");
        insertException(currentRunId, TEST_MONTH, "WARNING", "POLICY_DUPLICATE", "REJECTED",
                "policy-rejected");
        insertException(null, TEST_MONTH, "HIGH", "POLICY_MISSING", "NEW", "policy-realtime");

        insertAttributionImbalancedTransaction(TEST_MONTH, "current-month");
        insertAttributionImbalancedTransaction(TEST_MONTH.plusMonths(1), "other-month");

        insertCapMismatch(currentRunId, contractId);
        insertCapMismatch(otherRunId, contractId);

        FinalizeChecklistCounts counts = validationRunMapper.findFinalizeChecklistCounts(currentRunId);

        assertThat(counts.getIncompleteRunCount()).isZero();
        assertThat(counts.getJournalImbalanceCount()).isEqualTo(1);
        // 같은 검증월의 미처리 CRITICAL 3건: 현재 실행 1 + 다른 실행 1 + 실시간(run_id NULL) 1.
        // 다른 검증월·해결(RESOLVED) 건은 세지 않는다.
        assertThat(counts.getUnresolvedCriticalExceptionCount()).isEqualTo(3);
        // 같은 검증월의 미처리 정책 예외 2건: 배치 IN_REVIEW 1 + 실시간 NEW 1. 종결(REJECTED) 제외.
        assertThat(counts.getUnresolvedPolicyExceptionCount()).isEqualTo(2);
        assertThat(counts.getAttributionImbalanceCount()).isEqualTo(1);
        assertThat(counts.getCapDetailMismatchCount()).isEqualTo(1);
    }

    @Test
    void reportsStateConditionAsOneFailureForNonCompletedRun() {
        Long runId = createRunningRun(TEST_MONTH.plusMonths(2));

        FinalizeChecklistCounts counts = validationRunMapper.findFinalizeChecklistCounts(runId);

        assertThat(counts.getIncompleteRunCount()).isEqualTo(1);
    }

    @Test
    void fgcFun044_roundsEachCapDetailHalfUpBeforeComparingItsSum() {
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
        Long mismatchRunId = createCompletedRun(TEST_MONTH.plusMonths(3));
        Long matchedRunId = createCompletedRun(TEST_MONTH.plusMonths(4));

        // 2026-08-19 yslee - 상세행별 원 단위 HALF_UP 후 합산하는 확정 조건 검증
        // 기존 코드: 소수 상세금액을 먼저 합산해 0.5원 두 행을 1원으로 비교
        // 문제: 문서 기준의 행별 반올림 합계 2원과 달라 불일치 판정이 뒤바뀔 수 있음
        // 개선: 동일한 0.5원 두 행을 1원·2원 요약과 각각 비교해 경계값 판정 고정
        insertCapDetails(insertCapCheck(mismatchRunId, contractId, "1.00"), "0.50", "0.50");
        insertCapDetails(insertCapCheck(matchedRunId, contractId, "2.00"), "0.50", "0.50");

        assertThat(validationRunMapper.findFinalizeChecklistCounts(mismatchRunId)
                .getCapDetailMismatchCount()).isEqualTo(1);
        assertThat(validationRunMapper.findFinalizeChecklistCounts(matchedRunId)
                .getCapDetailMismatchCount()).isZero();
    }

    private Long createCompletedRun(LocalDate month) {
        Long id = createRunningRun(month);
        jdbcTemplate.update("""
                UPDATE fgc.validation_run
                   SET status = 'COMPLETED', current_step = 8, completed_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, id);
        return id;
    }

    private Long createRunningRun(LocalDate month) {
        int runNo = ThreadLocalRandom.current().nextInt(1, 1_000_000);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type)
                VALUES (?, ?, 'PRE_CONFIRM')
                RETURNING validation_run_id
                """, Long.class, month, runNo);
        jdbcTemplate.update("""
                UPDATE fgc.validation_run
                   SET status = 'RUNNING', current_step = 1, started_at = clock_timestamp()
                 WHERE validation_run_id = ?
                """, id);
        return id;
    }

    private void insertJournalImbalance(Long runId, Long contractId, String suffix) {
        Long headerId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header (
                    journal_no, journal_date, journal_type, source_entity_type, source_entity_id,
                    validation_run_id, contract_id, status
                ) VALUES (?, ?, 'EXPECTED_FC_PAYOUT', 'VALIDATION_RUN', ?, ?, ?, 'DRAFT')
                RETURNING journal_header_id
                """, Long.class, "FUN044-JRN-" + suffix + "-" + UUID.randomUUID(), TEST_MONTH,
                runId + "-" + suffix, runId, contractId);
        List<Long> accounts = jdbcTemplate.queryForList("""
                SELECT journal_account_id FROM fgc.journal_account
                 ORDER BY journal_account_id LIMIT 2
                """, Long.class);
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line
                    (journal_header_id, line_no, journal_account_id, debit_amount, credit_amount)
                VALUES (?, 1, ?, 100, 0), (?, 2, ?, 0, 90)
                """, headerId, accounts.get(0), headerId, accounts.get(1));
    }

    /** runId 가 NULL 이면 실시간 확정 경로, 아니면 배치 검출 경로를 흉내 낸다. 월은 두 경로 모두 남긴다(#330). */
    private void insertException(Long runId, LocalDate month, String severity, String type,
                                 String status, String suffix) {
        jdbcTemplate.update("""
                INSERT INTO fgc.exception_case (
                    exception_key, exception_type, severity, status, validation_run_id,
                    validation_month, source_entity_type, source_entity_id, title
                ) VALUES (?, ?, ?, ?, ?, ?, 'VALIDATION_RUN', ?, 'FUN-044 checklist test')
                """, "FUN044-EX-" + suffix + "-" + UUID.randomUUID(), type, severity, status,
                runId, month, runId + "-" + suffix);
    }

    private void insertAttributionImbalancedTransaction(LocalDate month, String suffix) {
        Long commissionItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1",
                Long.class);
        jdbcTemplate.update("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, commission_item_id,
                    settlement_month, amount, cashflow_type, status
                ) VALUES ('INSURER_TO_GA', 'GA_MANUAL_PAYMENT', ?, ?, ?, 100, 'PAYMENT', 'DRAFT')
                """, "FUN044-TX-" + suffix + "-" + UUID.randomUUID(), commissionItemId, month);
    }

    private void insertCapMismatch(Long runId, Long contractId) {
        insertCapCheck(runId, contractId, "100.00");
    }

    private Long insertCapCheck(Long runId, Long contractId, String includedAmount) {
        Long capRuleSetId = jdbcTemplate.queryForObject(
                "SELECT cap_rule_set_id FROM fgc.cap_rule_set ORDER BY cap_rule_set_id LIMIT 1", Long.class);
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.cap_check (
                    validation_run_id, contract_id, payment_stage, cap_rule_set_id, check_kind,
                    as_of_date, base_premium_amount, limit_amount, included_amount,
                    remaining_amount, result_status
                ) VALUES (?, ?, 'GA_TO_FC', ?, 'MONTHLY', ?, 100, 1200, ?::numeric, 1100, 'NORMAL')
                RETURNING cap_check_id
                """, Long.class, runId, contractId, capRuleSetId, TEST_MONTH, includedAmount);
    }

    private void insertCapDetails(Long capCheckId, String firstAmount, String secondAmount) {
        Long commissionItemId = jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1",
                Long.class);
        jdbcTemplate.update("""
                INSERT INTO fgc.cap_check_detail (
                    cap_check_id, detail_seq, commission_item_id, classification_snapshot,
                    amount, decision_reason, item_code, item_name
                ) SELECT ?, detail_seq, commission_item_id, 'INCLUDED', amount::numeric,
                         'FUN-044 rounding test', item_code, item_name
                    FROM fgc.commission_item
                    CROSS JOIN (VALUES (1, ?), (2, ?)) detail(detail_seq, amount)
                   WHERE commission_item_id = ?
                """, capCheckId, firstAmount, secondAmount, commissionItemId);
    }
}
