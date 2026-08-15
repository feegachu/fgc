package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.validation.batch.contract.JournalPostingPort;
import com.susukkang.fgc.validation.batch.contract.JournalPostingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * #142 journalPostingStep(Step 6a) 실제 DB 통합테스트. 정상 기표, 재실행 중복 방지,
 * 검증 실행 범위 제한(다른 실행·다른 월 데이터가 섞이지 않는지)을 확인한다.
 *
 * 클래스에 @Transactional을 붙이지 않는다 — JournalPersistenceServiceImpl.saveDraft()가
 * REQUIRES_NEW로 실제 커밋하기 때문에(JournalPersistenceServiceImplIntegrationTest와 같은
 * 이유), 테스트 트랜잭션에 걸어 두면 saveDraft가 아직 커밋 전인 validation_run/schedule_line을
 * 보지 못해 FK 위반이 난다. 대신 각 테스트가 만든 행을 @AfterEach에서 직접, 실제로 지운다.
 */
@SpringBootTest
class JournalPostingPortImplIntegrationTest {

    private static final LocalDate VALIDATION_MONTH = LocalDate.of(2091, 5, 1);

    @Autowired
    private JournalPostingPort journalPostingPort;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final String keyPrefix = "TEST-142-" + System.nanoTime() + "-";
    private final List<Long> createdValidationRunIds = new ArrayList<>();
    private final List<Long> createdScheduleHeaderIds = new ArrayList<>();
    private final List<DeactivatedSchedule> deactivatedSchedules = new ArrayList<>();

    private record DeactivatedSchedule(Long contractId, String paymentStage) {
    }

    @AfterEach
    void cleanUp() {
        TransactionTemplate cleanupTransaction = new TransactionTemplate(transactionManager);
        cleanupTransaction.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        cleanupTransaction.executeWithoutResult(status -> {
            // 확정 원장·지급 건 불변성 트리거를 정리 트랜잭션 안에서만 우회한다
            // (CommissionPaymentIntegrationTest#cleanupCommittedCapTestData와 같은 방식).
            jdbcTemplate.execute("SET LOCAL session_replication_role = replica");

            jdbcTemplate.update("""
                    DELETE FROM fgc.transaction_attribution
                     WHERE commission_transaction_id IN (
                         SELECT commission_transaction_id FROM fgc.commission_transaction
                          WHERE source_business_key LIKE ?
                     )
                    """, keyPrefix + "%");
            jdbcTemplate.update(
                    "DELETE FROM fgc.commission_transaction WHERE source_business_key LIKE ?", keyPrefix + "%");

            for (Long runId : createdValidationRunIds) {
                jdbcTemplate.update("""
                        DELETE FROM fgc.journal_line WHERE journal_header_id IN (
                            SELECT journal_header_id FROM fgc.journal_header WHERE validation_run_id = ?
                        )
                        """, runId);
                jdbcTemplate.update("DELETE FROM fgc.journal_header WHERE validation_run_id = ?", runId);
            }
            for (Long scheduleHeaderId : createdScheduleHeaderIds) {
                jdbcTemplate.update(
                        "DELETE FROM fgc.schedule_line WHERE schedule_header_id = ?", scheduleHeaderId);
                jdbcTemplate.update(
                        "DELETE FROM fgc.schedule_header WHERE schedule_header_id = ?", scheduleHeaderId);
            }
            for (DeactivatedSchedule deactivated : deactivatedSchedules) {
                jdbcTemplate.update("""
                        UPDATE fgc.schedule_header SET active_yn = true
                         WHERE contract_id = ? AND payment_stage = ?
                           AND schedule_purpose = 'OPERATIONAL' AND active_yn = false
                        """, deactivated.contractId(), deactivated.paymentStage());
            }
            for (Long runId : createdValidationRunIds) {
                jdbcTemplate.update("DELETE FROM fgc.validation_target WHERE validation_run_id = ?", runId);
                jdbcTemplate.update("DELETE FROM fgc.validation_run WHERE validation_run_id = ?", runId);
            }
        });
    }

    private ValidationStepContext newContext(Long validationRunId, LocalDate month) {
        ValidationJobContext job = new ValidationJobContext(
                month, 1L, ValidationRunType.MONTHLY, 3L, "req-142");
        return new ValidationStepContext(validationRunId, job);
    }

    private Long insertValidationRun(LocalDate month) {
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'MONTHLY', 'CREATED'
                  FROM fgc.validation_run WHERE validation_month = ?
                RETURNING validation_run_id
                """, Long.class, month, month);
        createdValidationRunIds.add(id);
        return id;
    }

    private Map<String, Object> anyContract() {
        return jdbcTemplate.queryForMap("""
                SELECT contract_id, product_offering_id, agent_id
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                """);
    }

    private void selectContract(Long validationRunId, Long contractId, Long productOfferingId) {
        jdbcTemplate.update("""
                INSERT INTO fgc.validation_target (
                    validation_run_id, contract_id, product_offering_id, selection_status, selection_reason
                ) VALUES (?, ?, ?, 'SELECTED', 'TEST')
                """, validationRunId, contractId, productOfferingId);
    }

    private Long anyPolicyVersionId() {
        return jdbcTemplate.queryForObject("""
                SELECT policy_version_id FROM fgc.policy_version WHERE status = 'ACTIVE'
                ORDER BY policy_version_id LIMIT 1
                """, Long.class);
    }

    private Long anyCommissionItemId() {
        return jdbcTemplate.queryForObject(
                "SELECT commission_item_id FROM fgc.commission_item ORDER BY commission_item_id LIMIT 1", Long.class);
    }

    private Long insertScheduleLine(Long contractId, String paymentStage, Long policyVersionId,
                                     Long commissionItemId, Long beneficiaryAgentId, LocalDate dueDate) {
        int deactivated = jdbcTemplate.update("""
                UPDATE fgc.schedule_header SET active_yn = false
                 WHERE contract_id = ? AND payment_stage = ? AND schedule_purpose = 'OPERATIONAL' AND active_yn = true
                """, contractId, paymentStage);
        if (deactivated > 0) {
            deactivatedSchedules.add(new DeactivatedSchedule(contractId, paymentStage));
        }
        Long scheduleHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_regime, schedule_purpose, active_yn
                ) VALUES (?, ?, ?, 999, 'CURRENT', 'OPERATIONAL', true)
                RETURNING schedule_header_id
                """, Long.class, contractId, paymentStage, policyVersionId);
        createdScheduleHeaderIds.add(scheduleHeaderId);
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_line (
                    schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, beneficiary_agent_id, basis_code, basis_amount,
                    calculation_type, rate_pct, expected_amount
                ) VALUES (?, 1, 1, 1, ?, ?, ?, 'TEST_AMOUNT', 100000.00, 'RATE', 50.000000, 50000.00)
                RETURNING schedule_line_id
                """, Long.class, scheduleHeaderId, dueDate, commissionItemId, beneficiaryAgentId);
    }

    /** DRAFT로 삽입 → 귀속행 삽입(합계=금액) → CONFIRMED로 전환(guard_commission_transaction_write 준수). */
    private Long insertConfirmedTransaction(Long contractId, String paymentStage, String sourceType,
                                             String sourceBusinessKeySuffix, Long recipientAgentId,
                                             Long commissionItemId, Long policyVersionId, LocalDate settlementMonth) {
        String sourceBusinessKey = keyPrefix + sourceBusinessKeySuffix;
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, source_contract_id,
                    recipient_agent_id, commission_item_id, policy_version_id, settlement_month,
                    due_date, amount, cashflow_type
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 40000.00, 'PAYMENT')
                RETURNING commission_transaction_id
                """, Long.class, paymentStage, sourceType, sourceBusinessKey, contractId,
                recipientAgentId, commissionItemId, policyVersionId, settlementMonth, settlementMonth);
        jdbcTemplate.update("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                    attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, attribution_method
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, 40000.00, 'INCLUDED', 'DIRECT')
                """, transactionId, contractId, settlementMonth, settlementMonth);
        jdbcTemplate.update(
                "UPDATE fgc.commission_transaction SET status = 'CONFIRMED' WHERE commission_transaction_id = ?",
                transactionId);
        return transactionId;
    }

    @Test
    void postsExpectedInsurerIncomeAndFcPayoutFromScheduleLines() {
        Map<String, Object> contract = anyContract();
        Long contractId = ((Number) contract.get("contract_id")).longValue();
        Long productOfferingId = ((Number) contract.get("product_offering_id")).longValue();
        Long agentId = ((Number) contract.get("agent_id")).longValue();
        Long policyVersionId = anyPolicyVersionId();
        Long commissionItemId = anyCommissionItemId();
        LocalDate dueDate = VALIDATION_MONTH.plusDays(9);

        Long validationRunId = insertValidationRun(VALIDATION_MONTH);
        selectContract(validationRunId, contractId, productOfferingId);
        insertScheduleLine(contractId, "INSURER_TO_GA", policyVersionId, commissionItemId, null, dueDate);
        insertScheduleLine(contractId, "GA_TO_FC", policyVersionId, commissionItemId, agentId, dueDate);

        JournalPostingResult result = journalPostingPort.post(newContext(validationRunId, VALIDATION_MONTH));

        assertThat(result.postedJournalCount()).isEqualTo(2);
        List<Map<String, Object>> journals = jdbcTemplate.queryForList("""
                SELECT journal_type, status FROM fgc.journal_header WHERE validation_run_id = ?
                ORDER BY journal_type
                """, validationRunId);
        assertThat(journals).hasSize(2);
        assertThat(journals).extracting(row -> row.get("journal_type"))
                .containsExactlyInAnyOrder("EXPECTED_FC_PAYOUT", "EXPECTED_INSURER_INCOME");
        assertThat(journals).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("POSTED"));
    }

    @Test
    void postsActualInsurerStatementAndConfirmedFcPayoutFromTransactions() {
        Map<String, Object> contract = anyContract();
        Long contractId = ((Number) contract.get("contract_id")).longValue();
        Long productOfferingId = ((Number) contract.get("product_offering_id")).longValue();
        Long agentId = ((Number) contract.get("agent_id")).longValue();
        Long policyVersionId = anyPolicyVersionId();
        Long commissionItemId = anyCommissionItemId();

        Long validationRunId = insertValidationRun(VALIDATION_MONTH);
        selectContract(validationRunId, contractId, productOfferingId);
        insertConfirmedTransaction(contractId, "INSURER_TO_GA", "INSURER_STATEMENT",
                "STMT", null, commissionItemId, policyVersionId, VALIDATION_MONTH);
        insertConfirmedTransaction(contractId, "GA_TO_FC", "GA_MANUAL_PAYMENT",
                "PAY", agentId, commissionItemId, policyVersionId, VALIDATION_MONTH);

        JournalPostingResult result = journalPostingPort.post(newContext(validationRunId, VALIDATION_MONTH));

        assertThat(result.postedJournalCount()).isEqualTo(2);
        List<Map<String, Object>> journals = jdbcTemplate.queryForList("""
                SELECT journal_type, status FROM fgc.journal_header WHERE validation_run_id = ?
                ORDER BY journal_type
                """, validationRunId);
        assertThat(journals).hasSize(2);
        assertThat(journals).extracting(row -> row.get("journal_type"))
                .containsExactlyInAnyOrder("ACTUAL_INSURER_STATEMENT", "CONFIRMED_FC_PAYOUT");
        assertThat(journals).allSatisfy(row -> assertThat(row.get("status")).isEqualTo("POSTED"));
    }

    @Test
    // 같은 실행을 재호출해도(예: 배치 재시작) 같은 원천은 새 journal_header를 만들지 않는다
    void rerunDoesNotDuplicateJournalsForTheSameSource() {
        Map<String, Object> contract = anyContract();
        Long contractId = ((Number) contract.get("contract_id")).longValue();
        Long productOfferingId = ((Number) contract.get("product_offering_id")).longValue();
        Long policyVersionId = anyPolicyVersionId();
        Long commissionItemId = anyCommissionItemId();
        LocalDate dueDate = VALIDATION_MONTH.plusDays(9);

        Long validationRunId = insertValidationRun(VALIDATION_MONTH);
        selectContract(validationRunId, contractId, productOfferingId);
        insertScheduleLine(contractId, "INSURER_TO_GA", policyVersionId, commissionItemId, null, dueDate);

        journalPostingPort.post(newContext(validationRunId, VALIDATION_MONTH));
        JournalPostingResult secondRun = journalPostingPort.post(newContext(validationRunId, VALIDATION_MONTH));

        assertThat(secondRun.postedJournalCount()).isEqualTo(1);
        Long journalCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM fgc.journal_header WHERE validation_run_id = ?", Long.class, validationRunId);
        assertThat(journalCount).isEqualTo(1L);
    }

    @Test
    // 다른 검증 실행(이 계약을 선별하지 않은 실행)의 결과에는 섞이지 않는다
    void scopesToSelectedContractsOfThisValidationRunOnly() {
        Map<String, Object> contract = anyContract();
        Long contractId = ((Number) contract.get("contract_id")).longValue();
        Long productOfferingId = ((Number) contract.get("product_offering_id")).longValue();
        Long policyVersionId = anyPolicyVersionId();
        Long commissionItemId = anyCommissionItemId();
        LocalDate dueDate = VALIDATION_MONTH.plusDays(9);

        Long targetRunId = insertValidationRun(VALIDATION_MONTH);
        Long otherRunId = insertValidationRun(VALIDATION_MONTH.plusMonths(1));
        // otherRunId는 이 계약을 선별하지 않는다 — targetRunId만 선별
        selectContract(targetRunId, contractId, productOfferingId);
        insertScheduleLine(contractId, "INSURER_TO_GA", policyVersionId, commissionItemId, null, dueDate);

        JournalPostingResult otherResult = journalPostingPort.post(newContext(otherRunId, VALIDATION_MONTH.plusMonths(1)));
        JournalPostingResult targetResult = journalPostingPort.post(newContext(targetRunId, VALIDATION_MONTH));

        assertThat(otherResult.postedJournalCount()).isZero();
        assertThat(targetResult.postedJournalCount()).isEqualTo(1);
    }
}
