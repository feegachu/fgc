package com.susukkang.fgc.exceptioncase.repository;

import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import com.susukkang.fgc.common.code.ExceptionStatus;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #380의 구 MyBatis {@code ExceptionCaseMapper}와 공용
 * {@code ExceptionCaseRepository}(네이티브 쿼리로 fgc.record_exception_detection을
 * 감싼 6개 메서드)가 같은 입력에 대해 완전히 동일한 exception_case 행을 만드는지
 * 직접 비교한다. 비교 실행을 서로 다른 검증월로 나눠 같은 시드의 종류·심각도·제목·본문을
 * 확인한다. #378 단건 탐지의 미조회·호출자 트랜잭션·같은 월 재검출·일괄 탐지 수렴도 검증한다.
 */
@SpringBootTest
@Transactional
class ExceptionCaseRepositoryDetectionIntegrationTest {

    private static final LocalDate LEGACY_MONTH = LocalDate.of(2097, 8, 1);
    private static final LocalDate NEW_MONTH = LocalDate.of(2097, 9, 1);

    @Autowired
    private ExceptionCaseMapper exceptionCaseMapper;
    @Autowired
    private ExceptionCaseRepository exceptionCaseRepository;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private EntityManager entityManager;

    @Test
    void arbitrageDetectionReturnsNullWhenValidationRunDoesNotExist() {
        assertThat(exceptionCaseRepository.insertArbitrageCandidate(
                Long.MAX_VALUE, 1L, 1L, "GA_TO_FC", null)).isNull();
        assertThat(exceptionCaseRepository.insertArbitrageReviewCase(
                "DATA_QUALITY", Long.MAX_VALUE, 1L, 1L, "GA_TO_FC", "확인 필요", null)).isNull();
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void arbitrageDetectionRequiresTheCallersTransaction() {
        assertThatThrownBy(() -> exceptionCaseRepository.insertArbitrageCandidate(
                1L, 1L, 1L, "GA_TO_FC", null)).isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> exceptionCaseRepository.insertArbitrageReviewCase(
                "DATA_QUALITY", 1L, 1L, 1L, "GA_TO_FC", "확인 필요", null))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void arbitrageSingleAndBulkDetectionShareBusinessKeyEvidenceAndReopening() {
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
        Long firstRunId = insertArbitrageValidationRun();
        Long firstResultId = insertArbitrageCandidateResult(firstRunId, contractId);
        Long caseId = exceptionCaseRepository.insertArbitrageCandidate(
                firstRunId, contractId, firstResultId, "GA_TO_FC", null);
        assertThat(caseId).isNotNull();
        assertThat(exceptionCaseRepository.insertFromArbitrageChecks(firstRunId)).isZero();
        var loaded = exceptionCaseRepository.findById(caseId).orElseThrow();
        assertThat(entityManager.contains(loaded)).isTrue();
        assertThat(exceptionCaseRepository.updateCaseAfterAction(
                caseId, ExceptionStatus.RESOLVED, null, OffsetDateTime.now())).isEqualTo(1);

        Long secondRunId = insertArbitrageValidationRun();
        Long secondResultId = insertArbitrageCandidateResult(secondRunId, contractId);
        assertThat(exceptionCaseRepository.insertArbitrageCandidate(
                secondRunId, contractId, secondResultId, "GA_TO_FC", null)).isEqualTo(caseId);
        assertThat(entityManager.contains(loaded)).isFalse();
        assertThat(exceptionCaseRepository.findById(caseId).orElseThrow().getStatus()).isEqualTo(ExceptionStatus.NEW);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT was_reopened FROM fgc.exception_occurrence
                 WHERE exception_case_id=? AND validation_run_id=?
                """, Boolean.class, caseId, secondRunId)).isTrue();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT evidence_snapshot -> 'description' = 'null'::jsonb FROM fgc.exception_occurrence
                 WHERE exception_case_id=? AND validation_run_id=?
                """, Boolean.class, caseId, secondRunId)).isTrue();
        assertThat(exceptionCaseRepository.insertFromArbitrageChecks(secondRunId)).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.exception_occurrence WHERE exception_case_id=?", Long.class, caseId)).isEqualTo(2L);
    }

    private Long insertArbitrageValidationRun() {
        // 재검출은 같은 검증월의 서로 다른 실행이다. 월당 활성 MONTHLY 실행 제약과 구분한다.
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'PRE_CONFIRM', 'CREATED'
                  FROM fgc.validation_run WHERE validation_month = ? RETURNING validation_run_id
                """, Long.class, NEW_MONTH, NEW_MONTH);
    }

    private Long insertArbitrageCandidateResult(Long runId, Long contractId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.arbitrage_check
                    (validation_run_id, contract_id, as_of_date, contract_month_no,
                     cumulative_paid_premium, net_difference_amount, standard_deduction_80_yn, result_status)
                VALUES (?, ?, ?, 12, 1000000, 100000, false, 'CANDIDATE')
                RETURNING arbitrage_check_id
                """, Long.class, runId, contractId, NEW_MONTH.plusMonths(1).minusDays(1));
    }

    @Test
    void bulkDetectionMethodsProduceIdenticalExceptionsToLegacyMapper() {
        List<Long> contractIds = jdbcTemplate.queryForList("""
                SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 3
                """, Long.class);
        assertThat(contractIds).hasSizeGreaterThanOrEqualTo(3);

        Long legacyRunId = insertValidationRun(LEGACY_MONTH);
        Long newRunId = insertValidationRun(NEW_MONTH);
        seedDetectionSources(legacyRunId, LEGACY_MONTH, contractIds);
        seedDetectionSources(newRunId, NEW_MONTH, contractIds);

        long legacyCap = exceptionCaseMapper.insertFromCapChecks(legacyRunId);
        long legacyArbitrage = exceptionCaseMapper.insertFromArbitrageChecks(legacyRunId);
        long legacyReconciliation = exceptionCaseMapper.insertFromReconciliationResults(legacyRunId);
        long legacyJournal = exceptionCaseMapper.insertFromJournalImbalances(legacyRunId);

        long newCap = exceptionCaseRepository.insertFromCapChecks(newRunId);
        long newArbitrage = exceptionCaseRepository.insertFromArbitrageChecks(newRunId);
        long newReconciliation = exceptionCaseRepository.insertFromReconciliationResults(newRunId);
        long newJournal = exceptionCaseRepository.insertFromJournalImbalances(newRunId);

        assertThat(newCap).isEqualTo(legacyCap).isEqualTo(2L);
        assertThat(newArbitrage).isEqualTo(legacyArbitrage).isEqualTo(3L);
        assertThat(newReconciliation).isEqualTo(legacyReconciliation).isEqualTo(2L);
        assertThat(newJournal).isEqualTo(legacyJournal).isEqualTo(1L);

        List<Map<String, Object>> legacyRows = normalizedExceptionRows(legacyRunId);
        List<Map<String, Object>> newRows = normalizedExceptionRows(newRunId);
        assertThat(newRows).containsExactlyInAnyOrderElementsOf(legacyRows);

        // 멱등성도 양쪽 다 같이 재확인 — 재호출은 전부 0(새 업무건 없음, 재검출 이력만 추가).
        assertThat(exceptionCaseMapper.insertFromCapChecks(legacyRunId)).isZero();
        assertThat(exceptionCaseRepository.insertFromCapChecks(newRunId)).isZero();
    }

    @Test
    void insertDataQualityCaseProducesIdenticalCaseToLegacyMapper() {
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
        Long legacyRunId = insertValidationRun(LEGACY_MONTH);
        Long newRunId = insertValidationRun(NEW_MONTH);

        Long legacyCaseId = exceptionCaseMapper.insertDataQualityCase(
                legacyRunId, contractId, "예상 스케줄 정합성 오류", "라인 수 불일치");
        Long newCaseId = exceptionCaseRepository.insertDataQualityCase(
                newRunId, contractId, "예상 스케줄 정합성 오류", "라인 수 불일치");

        assertThat(legacyCaseId).isNotNull();
        assertThat(newCaseId).isNotNull();
        assertThat(caseFields(newCaseId)).isEqualTo(caseFields(legacyCaseId));
    }

    @Test
    void insertCapCheckFailureProducesIdenticalCaseToLegacyMapper() {
        Long contractId = jdbcTemplate.queryForObject(
                "SELECT contract_id FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1", Long.class);
        Long legacyRunId = insertValidationRun(LEGACY_MONTH);
        Long newRunId = insertValidationRun(NEW_MONTH);

        Long legacyCaseId = exceptionCaseMapper.insertCapCheckFailure(
                legacyRunId, contractId, "INSURER_TO_GA", "계산 중 예외 발생");
        Long newCaseId = exceptionCaseRepository.insertCapCheckFailure(
                newRunId, contractId, "INSURER_TO_GA", "계산 중 예외 발생");

        assertThat(legacyCaseId).isNotNull();
        assertThat(newCaseId).isNotNull();
        assertThat(caseFields(newCaseId)).isEqualTo(caseFields(legacyCaseId));
    }

    private Map<String, Object> caseFields(Long exceptionCaseId) {
        // evidence_snapshot은 exception_case가 아니라 exception_occurrence 컬럼이라 여기서 보지 않는다.
        return jdbcTemplate.queryForMap("""
                SELECT exception_type, severity, source_entity_type, title, description
                  FROM fgc.exception_case
                 WHERE exception_case_id = ?
                """, exceptionCaseId);
    }

    // description은 비교하지 않는다 — JOURNAL_IMBALANCE의 description이 journal_no를
    // 포함하는데, journal_no는 uq_journal_no(전역 UNIQUE)라 실행마다 다른 값을 써야 해서
    // 두 실행 사이에 자연히 달라진다(버그가 아니라 journal_no 자체가 비교 대상이 아님).
    private List<Map<String, Object>> normalizedExceptionRows(Long validationRunId) {
        return jdbcTemplate.queryForList("""
                SELECT exception_type, reason_code, severity, source_entity_type, title
                  FROM fgc.exception_case
                 WHERE validation_run_id = ?
                 ORDER BY exception_type, source_entity_type, title
                """, validationRunId);
    }

    private Long insertValidationRun(LocalDate month) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'MONTHLY', 'CREATED'
                  FROM fgc.validation_run
                 WHERE validation_month = ?
                RETURNING validation_run_id
                """, Long.class, month, month);
    }

    private void seedDetectionSources(Long validationRunId, LocalDate month, List<Long> contractIds) {
        Long capRuleSetId = jdbcTemplate.queryForObject(
                "SELECT cap_rule_set_id FROM fgc.cap_rule_set ORDER BY cap_rule_set_id LIMIT 1", Long.class);
        insertCapCheck(validationRunId, contractIds.get(0), capRuleSetId, month, "INSURER_TO_GA", "VIOLATION", 130.0);
        insertCapCheck(validationRunId, contractIds.get(1), capRuleSetId, month, "GA_TO_FC", "REVIEW_REQUIRED", null);
        insertCapCheck(validationRunId, contractIds.get(2), capRuleSetId, month, "GA_TO_FC", "NORMAL", 50.0);

        Long contractId = contractIds.get(0);
        insertArbitrageCheck(validationRunId, contractId, month, "CANDIDATE", "차익거래 후보입니다");
        insertArbitrageCheck(validationRunId, contractId, month.plusDays(1), "REVIEW_REQUIRED", "환급률표 후보가 없습니다");
        insertArbitrageCheck(validationRunId, contractId, month.plusDays(2), "REVIEW_REQUIRED",
                "상품코드와 환급률표가 일치하지 않습니다");
        insertArbitrageCheck(validationRunId, contractId, month.plusDays(3), "CLEAR", "이상 없음");

        Long reconciliationRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (validation_run_id, settlement_month, payment_stage, status)
                VALUES (?, ?, 'GA_TO_FC', 'CREATED')
                RETURNING reconciliation_run_id
                """, Long.class, validationRunId, month);
        jdbcTemplate.update(
                "UPDATE fgc.reconciliation_run SET status = 'RUNNING', started_at = clock_timestamp() WHERE reconciliation_run_id = ?",
                reconciliationRunId);
        jdbcTemplate.update(
                "UPDATE fgc.reconciliation_run SET status = 'COMPLETED', completed_at = clock_timestamp() WHERE reconciliation_run_id = ?",
                reconciliationRunId);
        insertReconciliationResult(reconciliationRunId, contractId, "match-amount-" + validationRunId,
                "AMOUNT_DIFFERENCE", 100000, 90000);
        insertReconciliationResult(reconciliationRunId, contractId, "match-review-" + validationRunId,
                "REVIEW_REQUIRED", 100000, 0);
        insertReconciliationResult(reconciliationRunId, contractId, "match-normal-" + validationRunId,
                "MATCHED", 100000, 100000);

        insertJournalImbalance(validationRunId, contractId, month);
    }

    private void insertCapCheck(Long validationRunId, Long contractId, Long capRuleSetId, LocalDate month,
                                 String paymentStage, String resultStatus, Double usagePct) {
        jdbcTemplate.update("""
                INSERT INTO fgc.cap_check (
                    validation_run_id, contract_id, payment_stage, cap_rule_set_id,
                    check_kind, as_of_date, base_premium_amount, refund_12m_amount,
                    compliance_deduction_amount, limit_amount, included_amount,
                    remaining_amount, usage_pct, result_status, calculation_snapshot
                ) VALUES (?, ?, ?, ?, 'MONTHLY', ?, 100000, 0, 0,
                          1200000, 1300000, -100000, ?, ?, '{}'::jsonb)
                """, validationRunId, contractId, paymentStage, capRuleSetId, month, usagePct, resultStatus);
    }

    private void insertArbitrageCheck(Long validationRunId, Long contractId, LocalDate asOfDate,
                                       String resultStatus, String reason) {
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

    private void insertReconciliationResult(Long reconciliationRunId, Long contractId, String matchGroupKey,
                                             String resultType, long expectedAmount, long actualAmount) {
        jdbcTemplate.update("""
                INSERT INTO fgc.reconciliation_result (
                    reconciliation_run_id, match_group_key, contract_id,
                    result_type, expected_total_amount, actual_total_amount,
                    difference_amount, primary_reason_code
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, reconciliationRunId, matchGroupKey, contractId, resultType,
                expectedAmount, actualAmount, expectedAmount - actualAmount, resultType);
    }

    private void insertJournalImbalance(Long validationRunId, Long contractId, LocalDate month) {
        Long journalHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header (
                    journal_no, journal_date, journal_type,
                    source_entity_type, source_entity_id, revision_no,
                    validation_run_id, contract_id, status
                ) VALUES (?, ?, 'EXPECTED_FC_PAYOUT',
                          'SCHEDULE_LINE', ?,
                          COALESCE((SELECT MAX(revision_no) + 1
                                      FROM fgc.journal_header
                                     WHERE journal_type = 'EXPECTED_FC_PAYOUT'
                                       AND source_entity_type = 'SCHEDULE_LINE'
                                       AND source_entity_id = ?), 1),
                          ?, ?, 'DRAFT')
                RETURNING journal_header_id
                """, Long.class,
                "TEST-DETECT-IMBALANCE-" + validationRunId, month,
                "TEST-DETECT-SRC-" + validationRunId,
                "TEST-DETECT-SRC-" + validationRunId,
                validationRunId, contractId);

        List<Long> accountIds = jdbcTemplate.queryForList("""
                SELECT journal_account_id FROM fgc.journal_account ORDER BY journal_account_id LIMIT 2
                """, Long.class);
        assertThat(accountIds).hasSize(2);

        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (
                    journal_header_id, line_no, journal_account_id,
                    debit_amount, credit_amount, contract_id
                ) VALUES (?, 1, ?, 100000, 0, ?),
                         (?, 2, ?, 0, 90000, ?)
                """, journalHeaderId, accountIds.get(0), contractId, journalHeaderId, accountIds.get(1), contractId);
    }
}
