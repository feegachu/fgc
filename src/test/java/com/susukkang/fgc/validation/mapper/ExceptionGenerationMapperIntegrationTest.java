package com.susukkang.fgc.validation.mapper;

import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateRow;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class ExceptionGenerationMapperIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2099, 8, 1);

    @Autowired
    private ExceptionCaseMapper exceptionCaseMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createsAllValidationExceptionsIdempotently() {
        List<Long> contractIds = jdbcTemplate.queryForList("""
                SELECT contract_id
                  FROM fgc.insurance_contract
                 ORDER BY contract_id
                 LIMIT 3
                """, Long.class);
        assertThat(contractIds).hasSizeGreaterThanOrEqualTo(3);

        Long validationRunId = insertValidationRun();
        insertCapChecks(validationRunId, contractIds);
        insertArbitrageChecks(validationRunId, contractIds.get(0));
        insertReconciliationResults(validationRunId, contractIds.get(0));
        insertJournalImbalance(validationRunId, contractIds.get(0));

        long capCreated = exceptionCaseMapper.insertFromCapChecks(validationRunId);
        long arbitrageCreated = exceptionCaseMapper.insertFromArbitrageChecks(validationRunId);
        long reconciliationCreated =
                exceptionCaseMapper.insertFromReconciliationResults(validationRunId);
        long journalCreated = exceptionCaseMapper.insertFromJournalImbalances(validationRunId);

        assertThat(capCreated).isEqualTo(2L);
        assertThat(arbitrageCreated).isEqualTo(3L);
        assertThat(reconciliationCreated).isEqualTo(2L);
        assertThat(journalCreated).isEqualTo(1L);

        List<Map<String, Object>> exceptions = jdbcTemplate.queryForList("""
                SELECT exception_type, severity, source_entity_type
                  FROM fgc.exception_case
                 WHERE validation_run_id = ?
                 ORDER BY exception_type
                """, validationRunId);

        assertThat(exceptions).hasSize(8);
        assertThat(exceptions).extracting(row -> row.get("exception_type"))
                .containsExactlyInAnyOrder(
                        "CAP_VIOLATION",
                        "CAP_REVIEW_REQUIRED",
                        "ARBITRAGE_CANDIDATE",
                        "REFUND_TABLE_MISSING",
                        "PRODUCT_CODE_MISMATCH",
                        "JOURNAL_IMBALANCE",
                        "RECONCILIATION_MISMATCH",
                        "RECONCILIATION_MISMATCH");
        assertThat(exceptions)
                .anySatisfy(row -> {
                    assertThat(row.get("exception_type")).isEqualTo("CAP_VIOLATION");
                    assertThat(row.get("severity")).isEqualTo("CRITICAL");
                    assertThat(row.get("source_entity_type")).isEqualTo("CAP_CHECK");
                });

        assertThat(exceptionCaseMapper.insertFromCapChecks(validationRunId)).isZero();
        assertThat(exceptionCaseMapper.insertFromArbitrageChecks(validationRunId)).isZero();
        assertThat(exceptionCaseMapper.insertFromReconciliationResults(validationRunId)).isZero();
        assertThat(exceptionCaseMapper.insertFromJournalImbalances(validationRunId)).isZero();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.exception_case WHERE validation_run_id = ?",
                Integer.class,
                validationRunId);
        assertThat(count).isEqualTo(8);
    }

    // IF-API-42 — RECO-W01 "불일치 예외 일괄 생성" 버튼(수동, reconciliationRunId 기준)이
    // 월 검증 실행 단계의 자동 생성(insertFromReconciliationResults, validationRunId 기준)과
    // 정확히 같은 exception_key를 만들어서, 어느 경로가 먼저 실행됐든 서로 중복 생성하지
    // 않아야 한다(FUN-052). 수동 → 자동 순서.
    @Test
    void bulkCreateByReconciliationRunIsIdempotentAndCrossCompatibleWithValidationRunGeneration() {
        List<Long> contractIds = jdbcTemplate.queryForList("""
                SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                """, Long.class);
        assertThat(contractIds).isNotEmpty();
        Long contractId = contractIds.get(0);

        Long validationRunId = insertValidationRun();
        Long reconciliationRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (
                    validation_run_id, settlement_month, payment_stage, status
                ) VALUES (?, ?, 'GA_TO_FC', 'CREATED')
                RETURNING reconciliation_run_id
                """, Long.class, validationRunId, TEST_MONTH);
        insertReconciliationResult(reconciliationRunId, contractId,
                "bulk-amount", "AMOUNT_DIFFERENCE", 100000, 90000);
        insertReconciliationResult(reconciliationRunId, contractId,
                "bulk-review", "REVIEW_REQUIRED", 100000, 0);
        insertReconciliationResult(reconciliationRunId, contractId,
                "bulk-matched", "MATCHED", 100000, 100000);

        ReconciliationExceptionBulkCreateRow first =
                exceptionCaseMapper.bulkCreateFromReconciliationResultsByRun(reconciliationRunId);
        assertThat(first.getCandidateCount()).isEqualTo(2L);
        assertThat(first.getCreatedCount()).isEqualTo(2L);

        // 같은 실행에 다시 눌러도 새 예외가 생기지 않는다.
        ReconciliationExceptionBulkCreateRow second =
                exceptionCaseMapper.bulkCreateFromReconciliationResultsByRun(reconciliationRunId);
        assertThat(second.getCandidateCount()).isEqualTo(2L);
        assertThat(second.getCreatedCount()).isZero();

        // 월 검증 실행 단계의 자동 생성 경로로도 다시 시도해보면(같은 대사 실행이 그
        // validation_run_id에 연결돼 있으므로) 이미 수동으로 만든 예외와 키가 같아서
        // 새로 생기지 않는다.
        assertThat(exceptionCaseMapper.insertFromReconciliationResults(validationRunId))
                .isZero();

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.exception_case
                 WHERE source_entity_type = 'RECONCILIATION_RESULT'
                   AND validation_run_id = ?
                """, Integer.class, validationRunId);
        assertThat(count).isEqualTo(2);
    }

    // 위 테스트의 역순 — 자동 생성이 먼저 실행된 뒤 수동 버튼을 눌러도 중복이 생기면
    // 안 된다(코드리뷰 지적: 한쪽 순서만 검증하면 두 경로의 exception_key 형식이 나중에
    // 어긋나는 회귀를 놓칠 수 있다).
    @Test
    void bulkCreateByReconciliationRunSkipsExceptionsAlreadyCreatedByValidationRunGeneration() {
        List<Long> contractIds = jdbcTemplate.queryForList("""
                SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                """, Long.class);
        assertThat(contractIds).isNotEmpty();
        Long contractId = contractIds.get(0);

        Long validationRunId = insertValidationRun();
        Long reconciliationRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (
                    validation_run_id, settlement_month, payment_stage, status
                ) VALUES (?, ?, 'GA_TO_FC', 'CREATED')
                RETURNING reconciliation_run_id
                """, Long.class, validationRunId, TEST_MONTH);
        insertReconciliationResult(reconciliationRunId, contractId,
                "auto-first-amount", "AMOUNT_DIFFERENCE", 100000, 90000);
        insertReconciliationResult(reconciliationRunId, contractId,
                "auto-first-review", "REVIEW_REQUIRED", 100000, 0);
        insertReconciliationResult(reconciliationRunId, contractId,
                "auto-first-matched", "MATCHED", 100000, 100000);

        assertThat(exceptionCaseMapper.insertFromReconciliationResults(validationRunId))
                .isEqualTo(2L);

        ReconciliationExceptionBulkCreateRow manual =
                exceptionCaseMapper.bulkCreateFromReconciliationResultsByRun(reconciliationRunId);
        assertThat(manual.getCandidateCount()).isEqualTo(2L);
        assertThat(manual.getCreatedCount()).isZero();

        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM fgc.exception_case
                 WHERE source_entity_type = 'RECONCILIATION_RESULT'
                   AND validation_run_id = ?
                """, Integer.class, validationRunId);
        assertThat(count).isEqualTo(2);
    }

    private Long insertValidationRun() {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'MONTHLY', 'CREATED'
                  FROM fgc.validation_run
                 WHERE validation_month = ?
                RETURNING validation_run_id
                """, Long.class, TEST_MONTH, TEST_MONTH);
    }

    private void insertCapChecks(Long validationRunId, List<Long> contractIds) {
        Long capRuleSetId = jdbcTemplate.queryForObject(
                "SELECT cap_rule_set_id FROM fgc.cap_rule_set ORDER BY cap_rule_set_id LIMIT 1",
                Long.class);

        insertCapCheck(validationRunId, contractIds.get(0), capRuleSetId,
                "INSURER_TO_GA", "VIOLATION", 130.0);
        insertCapCheck(validationRunId, contractIds.get(1), capRuleSetId,
                "GA_TO_FC", "REVIEW_REQUIRED", null);
        insertCapCheck(validationRunId, contractIds.get(2), capRuleSetId,
                "GA_TO_FC", "NORMAL", 50.0);
    }

    private void insertCapCheck(
            Long validationRunId,
            Long contractId,
            Long capRuleSetId,
            String paymentStage,
            String resultStatus,
            Double usagePct) {
        jdbcTemplate.update("""
                INSERT INTO fgc.cap_check (
                    validation_run_id, contract_id, payment_stage, cap_rule_set_id,
                    check_kind, as_of_date, base_premium_amount, refund_12m_amount,
                    compliance_deduction_amount, limit_amount, included_amount,
                    remaining_amount, usage_pct, result_status, calculation_snapshot
                ) VALUES (?, ?, ?, ?, 'MONTHLY', ?, 100000, 0, 0,
                          1200000, 1300000, -100000, ?, ?, '{}'::jsonb)
                """, validationRunId, contractId, paymentStage, capRuleSetId,
                TEST_MONTH, usagePct, resultStatus);
    }

    private void insertArbitrageChecks(Long validationRunId, Long contractId) {
        insertArbitrageCheck(validationRunId, contractId, TEST_MONTH,
                "CANDIDATE", "차익거래 후보입니다");
        insertArbitrageCheck(validationRunId, contractId, TEST_MONTH.plusDays(1),
                "REVIEW_REQUIRED", "환급률표 후보가 없습니다");
        insertArbitrageCheck(validationRunId, contractId, TEST_MONTH.plusDays(2),
                "REVIEW_REQUIRED", "상품코드와 환급률표가 일치하지 않습니다");
        insertArbitrageCheck(validationRunId, contractId, TEST_MONTH.plusDays(3),
                "CLEAR", "이상 없음");
    }

    private void insertArbitrageCheck(
            Long validationRunId,
            Long contractId,
            LocalDate asOfDate,
            String resultStatus,
            String reason) {
        jdbcTemplate.update("""
                INSERT INTO fgc.arbitrage_check (
                    validation_run_id, contract_id, payment_stage, as_of_date,
                    contract_month_no, cumulative_paid_premium,
                    paid_commission_amount, planned_commission_amount,
                    included_surrender_value_amount, refund_addition_applied_yn,
                    surrender_value_source_type, net_difference_amount,
                    standard_deduction_80_yn, result_status, calculation_snapshot
                ) VALUES (?, ?, 'GA_TO_FC', ?, 40, 1000000,
                          600000, 500000, 0, false,
                          'NOT_APPLICABLE', 100000, false, ?,
                          jsonb_build_object('decisionReason', ?))
                """, validationRunId, contractId, asOfDate, resultStatus, reason);
    }

    private void insertReconciliationResults(Long validationRunId, Long contractId) {
        Long reconciliationRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (
                    validation_run_id, settlement_month, payment_stage, status
                ) VALUES (?, ?, 'GA_TO_FC', 'CREATED')
                RETURNING reconciliation_run_id
                """, Long.class, validationRunId, TEST_MONTH);

        jdbcTemplate.update("""
                UPDATE fgc.reconciliation_run
                   SET status = 'RUNNING',
                       started_at = clock_timestamp()
                 WHERE reconciliation_run_id = ?
                """, reconciliationRunId);

        jdbcTemplate.update("""
                UPDATE fgc.reconciliation_run
                   SET status = 'COMPLETED',
                       completed_at = clock_timestamp()
                 WHERE reconciliation_run_id = ?
                """, reconciliationRunId);

        insertReconciliationResult(reconciliationRunId, contractId,
                "match-amount", "AMOUNT_DIFFERENCE", 100000, 90000);
        insertReconciliationResult(reconciliationRunId, contractId,
                "match-review", "REVIEW_REQUIRED", 100000, 0);
        insertReconciliationResult(reconciliationRunId, contractId,
                "match-normal", "MATCHED", 100000, 100000);
    }

    private void insertReconciliationResult(
            Long reconciliationRunId,
            Long contractId,
            String matchGroupKey,
            String resultType,
            long expectedAmount,
            long actualAmount) {
        jdbcTemplate.update("""
                INSERT INTO fgc.reconciliation_result (
                    reconciliation_run_id, match_group_key, contract_id,
                    result_type, expected_total_amount, actual_total_amount,
                    difference_amount, primary_reason_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, reconciliationRunId, matchGroupKey, contractId,
                resultType, expectedAmount, actualAmount,
                expectedAmount - actualAmount, resultType);
    }

    private void insertJournalImbalance(Long validationRunId, Long contractId) {
        Long journalHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header (
                    journal_no, journal_date, journal_type,
                    source_entity_type, source_entity_id,
                    validation_run_id, contract_id, status
                ) VALUES (?, ?, 'EXPECTED_FC_PAYOUT',
                          'VALIDATION_RUN', ?, ?, ?, 'DRAFT')
                RETURNING journal_header_id
                """, Long.class,
                "TEST-IMBALANCE-" + validationRunId,
                TEST_MONTH,
                String.valueOf(validationRunId),
                validationRunId,
                contractId);

        List<Long> accountIds = jdbcTemplate.queryForList("""
                SELECT journal_account_id
                  FROM fgc.journal_account
                 ORDER BY journal_account_id
                 LIMIT 2
                """, Long.class);
        assertThat(accountIds).hasSize(2);

        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (
                    journal_header_id, line_no, journal_account_id,
                    debit_amount, credit_amount, contract_id
                ) VALUES (?, 1, ?, 100000, 0, ?),
                         (?, 2, ?, 0, 90000, ?)
                """,
                journalHeaderId, accountIds.get(0), contractId,
                journalHeaderId, accountIds.get(1), contractId);
    }
}
