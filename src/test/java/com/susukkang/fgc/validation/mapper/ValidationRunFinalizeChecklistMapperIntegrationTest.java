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

/** IF-API-50의 여섯 집계가 문서에 정의된 실행/월 범위를 지키는지 실제 PostgreSQL로 검증한다. */
@SpringBootTest
@Transactional
class ValidationRunFinalizeChecklistMapperIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2088, 1, 1);

    @Autowired
    private ValidationRunMapper validationRunMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void countsOnlyFailuresInTheDocumentedRunAndMonthScopes() {
        Long currentRunId = createCompletedRun(TEST_MONTH);
        Long otherRunId = createCompletedRun(TEST_MONTH);
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);

        insertJournalImbalance(currentRunId, contractId, "current");
        insertJournalImbalance(otherRunId, contractId, "other");

        insertException(currentRunId, "CRITICAL", "OTHER", "NEW", "critical-open");
        insertException(currentRunId, "CRITICAL", "OTHER", "RESOLVED", "critical-resolved");
        insertException(otherRunId, "CRITICAL", "OTHER", "NEW", "critical-other-run");
        insertException(currentRunId, "WARNING", "POLICY_MISSING", "IN_REVIEW", "policy-open");
        insertException(currentRunId, "WARNING", "POLICY_DUPLICATE", "REJECTED", "policy-rejected");

        insertAttributionImbalancedTransaction(TEST_MONTH, "current-month");
        insertAttributionImbalancedTransaction(TEST_MONTH.plusMonths(1), "other-month");

        insertCapMismatch(currentRunId, contractId);
        insertCapMismatch(otherRunId, contractId);

        FinalizeChecklistCounts counts = validationRunMapper.findFinalizeChecklistCounts(currentRunId);

        assertThat(counts.getIncompleteRunCount()).isZero();
        assertThat(counts.getJournalImbalanceCount()).isEqualTo(1);
        assertThat(counts.getUnresolvedCriticalExceptionCount()).isEqualTo(1);
        assertThat(counts.getUnresolvedPolicyExceptionCount()).isEqualTo(1);
        assertThat(counts.getAttributionImbalanceCount()).isEqualTo(1);
        assertThat(counts.getCapDetailMismatchCount()).isEqualTo(1);
    }

    @Test
    void reportsStateConditionAsOneFailureForNonCompletedRun() {
        Long runId = createRunningRun(TEST_MONTH.plusMonths(2));

        FinalizeChecklistCounts counts = validationRunMapper.findFinalizeChecklistCounts(runId);

        assertThat(counts.getIncompleteRunCount()).isEqualTo(1);
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

    private void insertException(Long runId, String severity, String type, String status, String suffix) {
        jdbcTemplate.update("""
                INSERT INTO fgc.exception_case (
                    exception_key, exception_type, severity, status, validation_run_id,
                    source_entity_type, source_entity_id, title
                ) VALUES (?, ?, ?, ?, ?, 'VALIDATION_RUN', ?, 'FUN-044 checklist test')
                """, "FUN044-EX-" + suffix + "-" + UUID.randomUUID(), type, severity, status,
                runId, runId + "-" + suffix);
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
        Long capRuleSetId = jdbcTemplate.queryForObject(
                "SELECT cap_rule_set_id FROM fgc.cap_rule_set ORDER BY cap_rule_set_id LIMIT 1", Long.class);
        jdbcTemplate.update("""
                INSERT INTO fgc.cap_check (
                    validation_run_id, contract_id, payment_stage, cap_rule_set_id, check_kind,
                    as_of_date, base_premium_amount, limit_amount, included_amount,
                    remaining_amount, result_status
                ) VALUES (?, ?, 'GA_TO_FC', ?, 'MONTHLY', ?, 100, 1200, 100, 1100, 'NORMAL')
                """, runId, contractId, capRuleSetId, TEST_MONTH);
    }
}
