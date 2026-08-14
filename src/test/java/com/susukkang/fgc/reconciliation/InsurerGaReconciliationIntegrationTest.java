package com.susukkang.fgc.reconciliation;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;
import com.susukkang.fgc.reconciliation.dto.InsurerGaMatchCandidate;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.service.InsurerGaReconciliationMatcher;
import com.susukkang.fgc.reconciliation.service.ReconciliationResultPersistenceService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 설명 : 실제 PostgreSQL에서 보험사→GA 대사 원천 필터와 매칭 결과를 검증
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@SpringBootTest
@Transactional
class InsurerGaReconciliationIntegrationTest {

    private static final LocalDate TEST_MONTH = LocalDate.of(2098, 8, 1);

    @Autowired
    private InsurerGaReconciliationMatcher matcher;

    @Autowired
    private ReconciliationResultPersistenceService persistenceService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void 기존_정규화_DB에서_운영_스케줄과_승인된_원수사_명세만_매칭한다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long otherInsurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true AND insurer_id <> ? ORDER BY insurer_id LIMIT 1", insurerId);
        Long contractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1", insurerId);
        Long secondContractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? AND contract_id <> ? ORDER BY contract_id LIMIT 1", insurerId, contractId);
        Long otherContractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1", otherInsurerId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1");
        Long contractAgentId = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contractId);
        String sourceAgentCode = insertAgentInsurerCode(insurerId, contractAgentId, "MATCHED");

        Long expectedMatched = insertSchedule(contractId, policyVersionId, commissionItemId, "650000", true, "OPERATIONAL", 1);
        Long expectedMissing = insertSchedule(secondContractId, policyVersionId, commissionItemId, "300000", true, "OPERATIONAL", 2);
        insertSchedule(contractId, policyVersionId, commissionItemId, "777000", false, "OPERATIONAL", 3);
        insertSchedule(contractId, policyVersionId, commissionItemId, "888000", true, "COMPARISON", 4);
        insertSchedule(otherContractId, policyVersionId, commissionItemId, "999000", true, "OPERATIONAL", 1);

        Long statementBatchId = insertStatementBatch(insurerId, TEST_MONTH, "VALIDATED", "VALID");
        Long actualMatched = insertActual(statementBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "650000", "MATCHED", sourceAgentCode, 1, "EXCLUDED", TEST_MONTH.plusDays(14));

        Long nextMonthBatchId = insertStatementBatch(insurerId, TEST_MONTH.plusMonths(1), "VALIDATED", "NEXT-MONTH");
        Long nextMonthActual = insertActual(nextMonthBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH.plusMonths(1), "100000", "NEXT-MONTH", sourceAgentCode);

        Long rejectedBatchId = insertStatementBatch(insurerId, TEST_MONTH, "REJECTED", "REJECTED");
        insertActual(rejectedBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "650000", "REJECTED", sourceAgentCode);
        Long otherInsurerBatchId = insertStatementBatch(otherInsurerId, TEST_MONTH, "VALIDATED", "OTHER-INSURER");
        insertActual(otherInsurerBatchId, otherInsurerId, otherContractId, commissionItemId,
                TEST_MONTH, "999000", "OTHER-INSURER", null);
        Long gaToFcActual = insertGaToFcActual(
                statementBatchId, insurerId, contractId, contractAgentId, commissionItemId, "650000");

        Long expectedJournalId = insertPostedJournal(
                "EXPECTED_INSURER_INCOME", "SCHEDULE_LINE", expectedMatched,
                contractId, commissionItemId, "650000", "EXPECTED_RECEIVABLE", "EXPECTED_INCOME");
        Long missingExpectedJournalId = insertPostedJournal(
                "EXPECTED_INSURER_INCOME", "SCHEDULE_LINE", expectedMissing,
                secondContractId, commissionItemId, "300000", "EXPECTED_RECEIVABLE", "EXPECTED_INCOME");
        Long actualTransactionId = id("""
                SELECT commission_transaction_id
                  FROM fgc.transaction_attribution
                 WHERE transaction_attribution_id = ?
                """, actualMatched);
        Long actualJournalId = insertPostedJournal(
                "ACTUAL_INSURER_STATEMENT", "COMMISSION_TRANSACTION", actualTransactionId,
                contractId, commissionItemId, "650000", "ACTUAL_RECEIVABLE", "ACTUAL_INCOME");

        Long reconciliationRunId = insertRunningReconciliationRun(insurerId, PaymentStage.INSURER_TO_GA);
        ReconciliationExecutionRequest executionRequest = new ReconciliationExecutionRequest(
                reconciliationRunId, null, TEST_MONTH, PaymentStage.INSURER_TO_GA, insurerId, null);
        List<InsurerGaMatchCandidate> results = matcher.match(executionRequest);

        assertThat(results).hasSize(2);
        assertThat(results).anySatisfy(result -> {
            assertThat(result.resultType()).isEqualTo(ReconciliationResultType.MATCHED);
            assertThat(result.scheduleLineIds()).containsExactly(expectedMatched);
            assertThat(result.transactionAttributionIds()).containsExactly(actualMatched);
            assertThat(result.expectedJournalHeaderIds()).containsExactly(expectedJournalId);
            assertThat(result.actualJournalHeaderIds()).containsExactly(actualJournalId);
        });
        assertThat(results).anySatisfy(result -> {
            assertThat(result.resultType()).isEqualTo(ReconciliationResultType.ACTUAL_MISSING);
            assertThat(result.scheduleLineIds()).containsExactly(expectedMissing);
        });
        assertThat(results).flatExtracting(InsurerGaMatchCandidate::transactionAttributionIds)
                .doesNotContain(nextMonthActual, gaToFcActual);

        // FGC-FUN-048-04: FUN-048-02가 만든 실제 후보를 결과·원천 연결 테이블까지 저장한다.
        persistenceService.persist(executionRequest, results);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.reconciliation_result WHERE reconciliation_run_id = ?",
                Integer.class,
                reconciliationRunId)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.reconciliation_match match
                  JOIN fgc.reconciliation_result result
                    ON result.reconciliation_result_id = match.reconciliation_result_id
                 WHERE result.reconciliation_run_id = ?
                """, Integer.class, reconciliationRunId)).isEqualTo(3);

        ActualMissingSnapshot actualMissing = jdbcTemplate.queryForObject("""
                SELECT reconciliation_result_id,
                       expected_total_amount,
                       actual_total_amount,
                       difference_amount,
                       detail_snapshot #>> '{expectedJournalHeaderIds,0}' AS expected_journal_id,
                       detail_snapshot #>> '{sources,0,scheduleLineId}' AS snapshot_schedule_line_id,
                       detail_snapshot #>> '{sources,0,matchedAmount}' AS snapshot_matched_amount,
                       detail_snapshot #>> '{sources,0,matchRole}' AS snapshot_match_role
                  FROM fgc.reconciliation_result
                 WHERE reconciliation_run_id = ?
                   AND result_type = 'ACTUAL_MISSING'
                """, (resultSet, rowNum) -> new ActualMissingSnapshot(
                resultSet.getLong("reconciliation_result_id"),
                resultSet.getBigDecimal("expected_total_amount"),
                resultSet.getBigDecimal("actual_total_amount"),
                resultSet.getBigDecimal("difference_amount"),
                resultSet.getLong("expected_journal_id"),
                resultSet.getLong("snapshot_schedule_line_id"),
                resultSet.getBigDecimal("snapshot_matched_amount"),
                resultSet.getString("snapshot_match_role")
        ), reconciliationRunId);
        assertThat(actualMissing.expectedTotalAmount()).isEqualByComparingTo("300000");
        assertThat(actualMissing.actualTotalAmount()).isZero();
        assertThat(actualMissing.differenceAmount()).isEqualByComparingTo("-300000");
        assertThat(actualMissing.expectedJournalId()).isEqualTo(missingExpectedJournalId);
        assertThat(actualMissing.snapshotScheduleLineId()).isEqualTo(expectedMissing);
        assertThat(actualMissing.snapshotMatchedAmount()).isEqualByComparingTo("300000");
        assertThat(actualMissing.snapshotMatchRole()).isEqualTo("EXPECTED");

        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM fgc.reconciliation_match
                 WHERE reconciliation_result_id = ?
                   AND match_seq = 1
                   AND schedule_line_id = ?
                   AND transaction_attribution_id IS NULL
                   AND matched_amount = 300000
                   AND match_role = 'EXPECTED'
                """, Integer.class, actualMissing.reconciliationResultId(), expectedMissing)).isEqualTo(1);
    }

    private record ActualMissingSnapshot(
            Long reconciliationResultId,
            BigDecimal expectedTotalAmount,
            BigDecimal actualTotalAmount,
            BigDecimal differenceAmount,
            Long expectedJournalId,
            Long snapshotScheduleLineId,
            BigDecimal snapshotMatchedAmount,
            String snapshotMatchRole
    ) {
    }

    @Test
    void 동일_키_실제_귀속행_두개를_합쳐_정상으로_숨기지_않고_DUPLICATE로_표시한다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long contractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1", insurerId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1");
        Long contractAgentId = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contractId);
        String sourceAgentCode = insertAgentInsurerCode(insurerId, contractAgentId, "DUPLICATE");
        Long expected = insertSchedule(contractId, policyVersionId, commissionItemId, "650000", true, "OPERATIONAL", 1);
        Long statementBatchId = insertStatementBatch(insurerId, TEST_MONTH, "AVAILABLE", "DUPLICATE");
        Long first = insertActual(statementBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "300000", "DUP-1", sourceAgentCode);
        Long second = insertActual(statementBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "350000", "DUP-2", sourceAgentCode);
        insertPostedJournal(
                "EXPECTED_INSURER_INCOME", "SCHEDULE_LINE", expected,
                contractId, commissionItemId, "650000", "EXPECTED_RECEIVABLE", "EXPECTED_INCOME");
        insertPostedJournal(
                "ACTUAL_INSURER_STATEMENT", "COMMISSION_TRANSACTION", transactionId(first),
                contractId, commissionItemId, "300000", "ACTUAL_RECEIVABLE", "ACTUAL_INCOME");
        insertPostedJournal(
                "ACTUAL_INSURER_STATEMENT", "COMMISSION_TRANSACTION", transactionId(second),
                contractId, commissionItemId, "350000", "ACTUAL_RECEIVABLE", "ACTUAL_INCOME");

        InsurerGaMatchCandidate result = matcher.match(new ReconciliationExecutionRequest(
                99L, 88L, TEST_MONTH, PaymentStage.INSURER_TO_GA, insurerId, null)).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.DUPLICATE);
        assertThat(result.expectedTotalAmount()).isEqualByComparingTo("650000");
        assertThat(result.actualTotalAmount()).isEqualByComparingTo("650000");
        assertThat(result.scheduleLineIds()).containsExactly(expected);
        assertThat(result.transactionAttributionIds()).containsExactly(first, second);
    }

    // 2026-08-13 yslee - POSTED 분개와 원수사 설계사코드 매핑 대사 조건 검증
    // 기존 코드: LEFT JOIN으로 분개가 없는 원천행도 대사에 포함되고 설계사코드는 판정에서 제외됨
    // 문제: 원장 추적이 불가능한 결과와 다른 설계사의 동일 금액 결과가 MATCHED로 저장될 수 있음
    // 개선: POSTED 분개 없는 행은 제외하고 유효한 코드 매핑이 다른 경우 AGENT_MISMATCH로 판정
    @Test
    void POSTED_분개가_없는_예상과_실제_원천은_대사대상에서_제외한다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long contractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1", insurerId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1");
        Long contractAgentId = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contractId);
        String sourceAgentCode = insertAgentInsurerCode(insurerId, contractAgentId, "NO-JOURNAL");

        insertSchedule(contractId, policyVersionId, commissionItemId, "650000", true, "OPERATIONAL", 1);
        Long statementBatchId = insertStatementBatch(insurerId, TEST_MONTH, "VALIDATED", "NO-JOURNAL");
        insertActual(statementBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "650000", "NO-JOURNAL", sourceAgentCode);

        List<InsurerGaMatchCandidate> results = matcher.match(new ReconciliationExecutionRequest(
                99L, 88L, TEST_MONTH, PaymentStage.INSURER_TO_GA, insurerId, null));

        assertThat(results).isEmpty();
    }

    @Test
    void 실제_원수사코드가_다른_설계사로_매핑되면_AGENT_MISMATCH다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long contractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1", insurerId);
        Long expectedAgentId = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contractId);
        Long otherAgentId = id("SELECT agent_id FROM fgc.agent WHERE agent_id <> ? ORDER BY agent_id LIMIT 1", expectedAgentId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1");
        String otherSourceAgentCode = insertAgentInsurerCode(insurerId, otherAgentId, "AGENT-MISMATCH");

        Long expected = insertSchedule(contractId, policyVersionId, commissionItemId, "650000", true, "OPERATIONAL", 1);
        Long statementBatchId = insertStatementBatch(insurerId, TEST_MONTH, "VALIDATED", "AGENT-MISMATCH");
        Long actual = insertActual(statementBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "650000", "AGENT-MISMATCH", otherSourceAgentCode);
        insertPostedJournal(
                "EXPECTED_INSURER_INCOME", "SCHEDULE_LINE", expected,
                contractId, commissionItemId, "650000", "EXPECTED_RECEIVABLE", "EXPECTED_INCOME");
        insertPostedJournal(
                "ACTUAL_INSURER_STATEMENT", "COMMISSION_TRANSACTION", transactionId(actual),
                contractId, commissionItemId, "650000", "ACTUAL_RECEIVABLE", "ACTUAL_INCOME");

        InsurerGaMatchCandidate result = matcher.match(new ReconciliationExecutionRequest(
                99L, 88L, TEST_MONTH, PaymentStage.INSURER_TO_GA, insurerId, null)).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.AGENT_MISMATCH);
        assertThat(result.expectedAgentId()).isEqualTo(expectedAgentId);
        assertThat(result.actualAgentId()).isEqualTo(otherAgentId);
        assertThat(result.actualSourceAgentCode()).isEqualTo(otherSourceAgentCode);
    }

    // 2026-08-13 yslee - PostgreSQL 실제 원천 회차 불일치 시나리오 추가
    // 기존 코드: commission_transaction에 실제 회차가 없어 REC-07을 통합 환경에서 재현할 수 없음
    // 문제: 예상 13회차·실제 14회차가 REVIEW_REQUIRED로만 남아 회차 불일치 건수 집계가 누락됨
    // 개선: 정규화된 실제 회차를 조회해 INSTALLMENT_MISMATCH와 양쪽 비교값을 검증
    @Test
    void 예상_13회차와_실제_14회차를_INSTALLMENT_MISMATCH로_저장할_후보를_만든다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long contractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1", insurerId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1");
        Long contractAgentId = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contractId);
        String sourceAgentCode = insertAgentInsurerCode(insurerId, contractAgentId, "INSTALLMENT-MISMATCH");

        Long expected = insertSchedule(
                contractId, policyVersionId, commissionItemId, "650000", true, "OPERATIONAL", 13);
        Long statementBatchId = insertStatementBatch(
                insurerId, TEST_MONTH, "VALIDATED", "INSTALLMENT-MISMATCH");
        Long actual = insertActual(
                statementBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "650000", "INSTALLMENT-MISMATCH", sourceAgentCode, 14);
        insertPostedJournal(
                "EXPECTED_INSURER_INCOME", "SCHEDULE_LINE", expected,
                contractId, commissionItemId, "650000", "EXPECTED_RECEIVABLE", "EXPECTED_INCOME");
        insertPostedJournal(
                "ACTUAL_INSURER_STATEMENT", "COMMISSION_TRANSACTION", transactionId(actual),
                contractId, commissionItemId, "650000", "ACTUAL_RECEIVABLE", "ACTUAL_INCOME");

        InsurerGaMatchCandidate result = matcher.match(new ReconciliationExecutionRequest(
                99L, 88L, TEST_MONTH, PaymentStage.INSURER_TO_GA, insurerId, null)).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.INSTALLMENT_MISMATCH);
        assertThat(result.installmentNo()).isEqualTo(13);
        assertThat(result.actualInstallmentNo()).isEqualTo(14);
    }

    // 2026-08-14 yslee - FUN-050 보험사→GA 0원·정확 일자 경계값 검증
    // 기존 코드: 동일 값 정상 일치와 회차 불일치만 PostgreSQL 통합 환경에서 검증
    // 문제: 1원·1일 차이가 원수사 명세 조회 이후 정상 일치로 처리될 위험이 있음
    // 개선: 실제 원수사 명세·분개 원천으로 금액과 날짜 경계를 검증
    @Test
    void FUN_050_실제_원수사_금액이_1원_작으면_AMOUNT_DIFFERENCE다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long contractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1", insurerId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1");
        Long contractAgentId = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contractId);
        String sourceAgentCode = insertAgentInsurerCode(insurerId, contractAgentId, "FUN-050-AMOUNT");

        Long expected = insertSchedule(contractId, policyVersionId, commissionItemId, "650000", true, "OPERATIONAL", 1);
        Long statementBatchId = insertStatementBatch(insurerId, TEST_MONTH, "VALIDATED", "FUN-050-AMOUNT");
        Long actual = insertActual(statementBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "649999", "FUN-050-AMOUNT", sourceAgentCode);
        insertPostedJournal(
                "EXPECTED_INSURER_INCOME", "SCHEDULE_LINE", expected,
                contractId, commissionItemId, "650000", "EXPECTED_RECEIVABLE", "EXPECTED_INCOME");
        insertPostedJournal(
                "ACTUAL_INSURER_STATEMENT", "COMMISSION_TRANSACTION", transactionId(actual),
                contractId, commissionItemId, "649999", "ACTUAL_RECEIVABLE", "ACTUAL_INCOME");

        InsurerGaMatchCandidate result = matcher.match(new ReconciliationExecutionRequest(
                50L, null, TEST_MONTH, PaymentStage.INSURER_TO_GA, insurerId, null)).getFirst();

        assertThat(result.resultType()).isEqualTo(ReconciliationResultType.AMOUNT_DIFFERENCE);
        assertThat(result.differenceAmount()).isEqualByComparingTo("-1");
    }

    @Test
    void FUN_050_원수사_지급일이_하루_다르면_정확일치로_합치지_않는다() {
        Long insurerId = id("SELECT insurer_id FROM fgc.insurer WHERE active_yn = true ORDER BY insurer_id LIMIT 1");
        Long contractId = id("SELECT contract_id FROM fgc.insurance_contract WHERE insurer_id = ? ORDER BY contract_id LIMIT 1", insurerId);
        Long policyVersionId = id("SELECT policy_version_id FROM fgc.policy_version ORDER BY policy_version_id LIMIT 1");
        Long commissionItemId = id("SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1");
        Long contractAgentId = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id = ?", contractId);
        String sourceAgentCode = insertAgentInsurerCode(insurerId, contractAgentId, "FUN-050-DATE");

        Long expected = insertSchedule(contractId, policyVersionId, commissionItemId, "650000", true, "OPERATIONAL", 1);
        Long statementBatchId = insertStatementBatch(insurerId, TEST_MONTH, "VALIDATED", "FUN-050-DATE");
        Long actual = insertActual(statementBatchId, insurerId, contractId, commissionItemId,
                TEST_MONTH, "650000", "FUN-050-DATE", sourceAgentCode, TEST_MONTH.plusDays(15));
        insertPostedJournal(
                "EXPECTED_INSURER_INCOME", "SCHEDULE_LINE", expected,
                contractId, commissionItemId, "650000", "EXPECTED_RECEIVABLE", "EXPECTED_INCOME");
        insertPostedJournal(
                "ACTUAL_INSURER_STATEMENT", "COMMISSION_TRANSACTION", transactionId(actual),
                contractId, commissionItemId, "650000", "ACTUAL_RECEIVABLE", "ACTUAL_INCOME");

        List<InsurerGaMatchCandidate> results = matcher.match(new ReconciliationExecutionRequest(
                50L, null, TEST_MONTH, PaymentStage.INSURER_TO_GA, insurerId, null));

        assertThat(results).extracting(InsurerGaMatchCandidate::resultType)
                .containsExactly(ReconciliationResultType.ACTUAL_MISSING, ReconciliationResultType.EXPECTED_MISSING);
    }

    private Long insertSchedule(
            Long contractId,
            Long policyVersionId,
            Long commissionItemId,
            String amount,
            boolean active,
            String purpose,
            int installmentNo
    ) {
        Long headerId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, scenario_code, schedule_regime, active_yn
                ) VALUES (?, 'INSURER_TO_GA', ?, ?, ?, ?, 'CURRENT', ?)
                RETURNING schedule_header_id
                """, Long.class,
                contractId,
                policyVersionId,
                purpose.equals("OPERATIONAL") ? 100 + installmentNo : 200 + installmentNo,
                purpose,
                purpose.equals("OPERATIONAL") ? null : "IT-048-02",
                active
        );
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_line (
                    schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, basis_code, basis_amount, calculation_type, fixed_amount,
                    expected_amount
                ) VALUES (?, 1, ?, ?, ?, ?, 'IT_RECONCILIATION', ?, 'FIXED', ?, ?)
                RETURNING schedule_line_id
                """, Long.class,
                headerId, installmentNo, installmentNo, TEST_MONTH.plusDays(14),
                commissionItemId, new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount)
        );
    }

    private Long insertStatementBatch(Long insurerId, LocalDate month, String status, String suffix) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.statement_batch (
                    insurer_id, settlement_month, statement_type, external_statement_no,
                    received_on, source_ref, status
                ) VALUES (?, ?, 'INSURER_COMMISSION', ?, ?, ?, ?)
                RETURNING statement_batch_id
                """, Long.class,
                insurerId, month, "IT-048-02-" + suffix, month.plusDays(20), "IT-048-02", status
        );
    }

    private Long insertActual(
            Long statementBatchId,
            Long insurerId,
            Long contractId,
            Long commissionItemId,
            LocalDate month,
            String amount,
            String suffix,
            String sourceAgentCode
    ) {
        return insertActual(
                statementBatchId, insurerId, contractId, commissionItemId,
                month, amount, suffix, sourceAgentCode, 1, "INCLUDED", month.plusDays(14));
    }

    private Long insertActual(
            Long statementBatchId,
            Long insurerId,
            Long contractId,
            Long commissionItemId,
            LocalDate month,
            String amount,
            String suffix,
            String sourceAgentCode,
            Integer installmentNo
    ) {
        return insertActual(
                statementBatchId, insurerId, contractId, commissionItemId,
                month, amount, suffix, sourceAgentCode, installmentNo, "INCLUDED", month.plusDays(14));
    }

    private Long insertActual(
            Long statementBatchId,
            Long insurerId,
            Long contractId,
            Long commissionItemId,
            LocalDate month,
            String amount,
            String suffix,
            String sourceAgentCode,
            LocalDate dueDate
    ) {
        return insertActual(
                statementBatchId, insurerId, contractId, commissionItemId,
                month, amount, suffix, sourceAgentCode, 1, "INCLUDED", dueDate);
    }

    private Long insertActual(
            Long statementBatchId,
            Long insurerId,
            Long contractId,
            Long commissionItemId,
            LocalDate month,
            String amount,
            String suffix,
            String sourceAgentCode,
            Integer installmentNo,
            String inclusionStatus,
            LocalDate dueDate
    ) {
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    statement_batch_id, payment_stage, source_type, source_business_key,
                    insurer_id, commission_item_id, installment_no, settlement_month, due_date,
                    amount, cashflow_type, status
                ) VALUES (?, 'INSURER_TO_GA', 'INSURER_STATEMENT', ?, ?, ?, ?, ?, ?, ?, 'PAYMENT', 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class,
                statementBatchId, "IT-048-02:" + suffix, insurerId, commissionItemId,
                installmentNo, month, dueDate, new BigDecimal(amount)
        );
        Long attributionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                    source_agent_code, attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, exclusion_type_snapshot, attribution_method
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, ?, ?, ?,
                          CASE WHEN ? = 'EXCLUDED' THEN 'COMPLIANCE_3PCT' ELSE NULL END,
                          'DIRECT')
                RETURNING transaction_attribution_id
                """, Long.class, transactionId, contractId, sourceAgentCode,
                dueDate, month, new BigDecimal(amount), inclusionStatus, inclusionStatus);
        jdbcTemplate.update("""
                UPDATE fgc.commission_transaction
                   SET status = 'CONFIRMED'
                 WHERE commission_transaction_id = ?
                """, transactionId);
        return attributionId;
    }

    private Long transactionId(Long attributionId) {
        return id("""
                SELECT commission_transaction_id
                  FROM fgc.transaction_attribution
                 WHERE transaction_attribution_id = ?
                """, attributionId);
    }

    private String insertAgentInsurerCode(Long insurerId, Long agentId, String suffix) {
        String sourceAgentCode = "IT-048-02-" + suffix + "-" + agentId;
        jdbcTemplate.update("""
                INSERT INTO fgc.agent_insurer_code (
                    insurer_id, agent_id, insurer_agent_code, code_status,
                    effective_from, effective_to, source_ref
                ) VALUES (?, ?, ?, 'ACTIVE', ?, NULL, 'IT-048-02')
                """, insurerId, agentId, sourceAgentCode, TEST_MONTH);
        return sourceAgentCode;
    }

    private Long insertGaToFcActual(
            Long statementBatchId,
            Long insurerId,
            Long contractId,
            Long agentId,
            Long commissionItemId,
            String amount
    ) {
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    statement_batch_id, payment_stage, source_type, source_business_key,
                    insurer_id, recipient_agent_id, commission_item_id, settlement_month,
                    due_date, amount, cashflow_type, status
                ) VALUES (?, 'GA_TO_FC', 'GA_CONFIRMED_PAYMENT', 'IT-048-02:GA-TO-FC',
                          ?, ?, ?, ?, ?, ?, 'PAYMENT', 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class,
                statementBatchId, insurerId, agentId, commissionItemId, TEST_MONTH,
                TEST_MONTH.plusDays(14), new BigDecimal(amount)
        );
        Long attributionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                    agent_id, attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, attribution_method
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, ?, ?, 'INCLUDED', 'DIRECT')
                RETURNING transaction_attribution_id
                """, Long.class,
                transactionId, contractId, agentId, TEST_MONTH.plusDays(14), TEST_MONTH,
                new BigDecimal(amount)
        );
        jdbcTemplate.update("UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?",
                transactionId);
        return attributionId;
    }

    private Long insertPostedJournal(
            String journalType,
            String sourceEntityType,
            Long sourceEntityId,
            Long contractId,
            Long commissionItemId,
            String amount,
            String debitAccountCode,
            String creditAccountCode
    ) {
        Long journalHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header (
                    journal_no, journal_date, journal_type, source_entity_type,
                    source_entity_id, contract_id, status
                ) VALUES (?, ?, ?, ?, ?, ?, 'DRAFT')
                RETURNING journal_header_id
                """, Long.class,
                "IT-048-02-" + journalType + "-" + sourceEntityId,
                TEST_MONTH.plusDays(14), journalType, sourceEntityType,
                String.valueOf(sourceEntityId), contractId
        );
        Long debitAccountId = id("SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?", debitAccountCode);
        Long creditAccountId = id("SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?", creditAccountCode);
        BigDecimal journalAmount = new BigDecimal(amount);
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (
                    journal_header_id, line_no, journal_account_id, debit_amount, credit_amount,
                    contract_id, payment_stage, commission_item_id
                ) VALUES (?, 1, ?, ?, 0, ?, 'INSURER_TO_GA', ?),
                         (?, 2, ?, 0, ?, ?, 'INSURER_TO_GA', ?)
                """,
                journalHeaderId, debitAccountId, journalAmount, contractId, commissionItemId,
                journalHeaderId, creditAccountId, journalAmount, contractId, commissionItemId
        );
        jdbcTemplate.update("UPDATE fgc.journal_header SET status = 'POSTED' WHERE journal_header_id = ?",
                journalHeaderId);
        return journalHeaderId;
    }

    private Long id(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private Long insertRunningReconciliationRun(Long insurerId, PaymentStage paymentStage) {
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.reconciliation_run (settlement_month, payment_stage, insurer_id)
                VALUES (?, ?, ?)
                RETURNING reconciliation_run_id
                """, Long.class, TEST_MONTH, paymentStage.name(), insurerId);
        jdbcTemplate.update("""
                UPDATE fgc.reconciliation_run
                   SET status = 'RUNNING', started_at = clock_timestamp()
                 WHERE reconciliation_run_id = ?
                """, runId);
        return runId;
    }
}
