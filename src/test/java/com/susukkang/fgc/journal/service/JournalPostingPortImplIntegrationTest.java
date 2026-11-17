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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * #142 journalPostingStep(Step 6a) 실제 DB 통합테스트. 정상 기표, 재실행 중복 방지,
 * 검증 실행 범위 제한(다른 실행·다른 월 데이터가 섞이지 않는지)을 확인한다.
 *
 * 클래스에 @Transactional을 붙이지 않고 실제 기표 커밋과 재실행을 확인한다.
 * 배치 초안은 건별 커밋하고, 단계 실패 후 남은 초안을 중복 없이 다시 기표한다.
 * 각 테스트가 만든 행은 @AfterEach에서 직접 정리한다.
 */
@SpringBootTest
class JournalPostingPortImplIntegrationTest {

    private static final LocalDate VALIDATION_MONTH = LocalDate.of(2091, 5, 1);

    @Autowired
    private JournalPostingPort journalPostingPort;
    @Autowired
    private JournalPostingPortImpl expectedJournals;
    @Autowired
    private com.susukkang.fgc.exceptioncase.repository.ExceptionPaymentRepository exceptionPayments;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final String keyPrefix = "TEST-142-" + System.nanoTime() + "-";
    private final List<Long> createdValidationRunIds = new ArrayList<>();
    private final List<Long> createdScheduleHeaderIds = new ArrayList<>();
    private final List<Long> deactivatedScheduleHeaderIds = new ArrayList<>();

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
                jdbcTemplate.update("DELETE FROM fgc.reconciliation_run WHERE validation_run_id=?", runId);
                jdbcTemplate.update("DELETE FROM fgc.journal_correction_group WHERE original_journal_header_id IN "
                        + "(SELECT journal_header_id FROM fgc.journal_header WHERE validation_run_id=?)", runId);
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
            // 이 테스트가 비활성화시킨 "그 행"만 정확히 되살린다 — contract_id·payment_stage로만
            // 되짚으면 원래부터 비활성이던 이전 버전 스케줄까지 함께 활성화돼 버린다(코드리뷰 반영).
            for (Long scheduleHeaderId : deactivatedScheduleHeaderIds) {
                jdbcTemplate.update(
                        "UPDATE fgc.schedule_header SET active_yn = true WHERE schedule_header_id = ?",
                        scheduleHeaderId);
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
        List<Long> previousActiveIds = jdbcTemplate.queryForList("""
                SELECT schedule_header_id FROM fgc.schedule_header
                 WHERE contract_id = ? AND payment_stage = ? AND schedule_purpose = 'OPERATIONAL' AND active_yn = true
                """, Long.class, contractId, paymentStage);
        if (!previousActiveIds.isEmpty()) {
            Long previousActiveId = previousActiveIds.get(0);
            jdbcTemplate.update(
                    "UPDATE fgc.schedule_header SET active_yn = false WHERE schedule_header_id = ?", previousActiveId);
            deactivatedScheduleHeaderIds.add(previousActiveId);
        }
        Integer version = jdbcTemplate.queryForObject("SELECT coalesce(max(schedule_version_no),0)+1 FROM fgc.schedule_header "
                + "WHERE contract_id=? AND payment_stage=? AND schedule_purpose='OPERATIONAL'", Integer.class, contractId, paymentStage);
        Long scheduleHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_regime, schedule_purpose, active_yn
                ) VALUES (?, ?, ?, ?, 'CURRENT', 'OPERATIONAL', true)
                RETURNING schedule_header_id
                """, Long.class, contractId, paymentStage, policyVersionId, version);
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
    void failedBatchKeepsItsDraftForAnIdempotentRestart() {
        var contract = anyContract();
        Long contractId = ((Number) contract.get("contract_id")).longValue();
        Long run = insertValidationRun(VALIDATION_MONTH);
        selectContract(run, contractId, ((Number) contract.get("product_offering_id")).longValue());
        insertScheduleLine(contractId, "GA_TO_FC", anyPolicyVersionId(), anyCommissionItemId(),
                ((Number) contract.get("agent_id")).longValue(), VALIDATION_MONTH.plusDays(9));

        var transaction = new TransactionTemplate(transactionManager);
        var context = newContext(run, VALIDATION_MONTH);
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            assertThat(journalPostingPort.post(newContext(run, VALIDATION_MONTH)).postedJournalCount()).isEqualTo(1);
            throw new IllegalStateException("posting rollback probe");
        })).isInstanceOf(IllegalStateException.class).hasMessage("posting rollback probe");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.journal_header WHERE validation_run_id=?",
                Long.class, run)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fgc.journal_header WHERE validation_run_id=?",
                String.class, run)).isEqualTo("DRAFT");
        assertThat(journalPostingPort.post(context).postedJournalCount()).isEqualTo(1);
        assertThat(journalPostingPort.post(context).postedJournalCount()).isZero();
    }

    @Test
    void regeneratedExpectedScheduleReversesItsOldJournalAndLinksTheReplacementAtomically() {
        var contract = anyContract();
        Long contractId = ((Number) contract.get("contract_id")).longValue();
        Long actor = jdbcTemplate.queryForObject("SELECT user_id FROM fgc.app_user WHERE login_id='settle01'", Long.class);
        Long run = insertValidationRun(VALIDATION_MONTH);
        selectContract(run, contractId, ((Number) contract.get("product_offering_id")).longValue());
        Long policy = anyPolicyVersionId();
        Long item = anyCommissionItemId();
        Long agent = ((Number) contract.get("agent_id")).longValue();
        Long oldLine = insertScheduleLine(contractId, "GA_TO_FC", policy, item, agent, VALIDATION_MONTH.plusDays(9));
        jdbcTemplate.update("UPDATE fgc.schedule_line SET expected_amount=100 WHERE schedule_line_id=?", oldLine);
        journalPostingPort.post(newContext(run, VALIDATION_MONTH));
        Long original = jdbcTemplate.queryForObject("SELECT journal_header_id FROM fgc.journal_header WHERE validation_run_id=?",
                Long.class, run);
        Long newLine = insertScheduleLine(contractId, "GA_TO_FC", policy, item, agent, VALIDATION_MONTH.plusDays(9));
        jdbcTemplate.update("UPDATE fgc.schedule_line SET expected_amount=80 WHERE schedule_line_id=?", newLine);
        var tx = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
            expectedJournals.postExpectedForContract(contractId, run, VALIDATION_MONTH, actor);
            throw new IllegalStateException("schedule correction rollback");
        })).isInstanceOf(IllegalStateException.class);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fgc.journal_header WHERE journal_header_id=?",
                String.class, original)).isEqualTo("POSTED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.journal_header WHERE validation_run_id=?",
                Long.class, run)).isEqualTo(1);

        tx.executeWithoutResult(status -> expectedJournals.postExpectedForContract(contractId, run, VALIDATION_MONTH, actor));
        tx.executeWithoutResult(status -> expectedJournals.postExpectedForContract(contractId, run, VALIDATION_MONTH, actor));
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fgc.journal_header WHERE journal_header_id=?",
                String.class, original)).isEqualTo("REVERSED");
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.journal_header WHERE validation_run_id=? AND journal_type='REVERSAL'",
                Long.class, run)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT count(*) FROM fgc.journal_correction_group g
                JOIN fgc.journal_header reversal ON reversal.correction_group_key=g.correction_group_key
                    AND reversal.reversal_of_id=g.original_journal_header_id
                JOIN fgc.journal_header replacement ON replacement.correction_group_key=g.correction_group_key
                    AND replacement.source_entity_type='SCHEDULE_LINE' AND replacement.source_entity_id=?
                WHERE g.original_journal_header_id=?
                """, Long.class, newLine.toString(), original)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sum(l.debit_amount) FROM fgc.journal_header h JOIN fgc.journal_line l USING(journal_header_id)
                WHERE h.validation_run_id=? AND h.journal_type='EXPECTED_FC_PAYOUT' AND h.status='POSTED'
                """, java.math.BigDecimal.class, run)).isEqualByComparingTo("80");
        Long thirdLine = insertScheduleLine(contractId, "GA_TO_FC", policy, item, agent, VALIDATION_MONTH.plusDays(9));
        jdbcTemplate.update("UPDATE fgc.schedule_line SET expected_amount=60 WHERE schedule_line_id=?", thirdLine);
        tx.executeWithoutResult(status -> expectedJournals.postExpectedForContract(contractId, run, VALIDATION_MONTH, actor));
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.journal_header WHERE validation_run_id=? AND journal_type='REVERSAL'",
                Long.class, run)).isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT sum(l.debit_amount) FROM fgc.journal_header h JOIN fgc.journal_line l USING(journal_header_id)
                WHERE h.validation_run_id=? AND h.journal_type='EXPECTED_FC_PAYOUT' AND h.status='POSTED'
                """, java.math.BigDecimal.class, run)).isEqualByComparingTo("60");
    }

    @Test
    void scheduleCorrectionLocksTheLatestReconciliationsOfBothPaymentStages() {
        var contract = anyContract();
        Long contractId = ((Number) contract.get("contract_id")).longValue();
        Long insurer = jdbcTemplate.queryForObject("SELECT insurer_id FROM fgc.insurance_contract WHERE contract_id=?", Long.class, contractId);
        Long run = insertValidationRun(VALIDATION_MONTH);
        var ids = new ArrayList<Long>();
        for (String stage : List.of("INSURER_TO_GA", "GA_TO_FC")) {
            Long reconciliation = jdbcTemplate.queryForObject("""
                    INSERT INTO fgc.reconciliation_run(settlement_month,payment_stage,insurer_id,validation_run_id)
                    VALUES (?,?,?,?) RETURNING reconciliation_run_id
                    """, Long.class, VALIDATION_MONTH, stage, insurer, run);
            jdbcTemplate.update("UPDATE fgc.reconciliation_run SET status='RUNNING',started_at=now() WHERE reconciliation_run_id=?", reconciliation);
            jdbcTemplate.update("UPDATE fgc.reconciliation_run SET status='COMPLETED',completed_at=now() WHERE reconciliation_run_id=?", reconciliation);
            ids.add(reconciliation);
        }
        var source = new com.susukkang.fgc.exceptioncase.repository.ExceptionPaymentRepository.Source(
                1L, "CAP_VIOLATION", contractId, "CAP_CHECK", "1", run);
        var scope = new com.susukkang.fgc.exceptioncase.repository.ExceptionPaymentRepository.CapScope(
                1L, contractId, com.susukkang.fgc.common.code.PaymentStage.GA_TO_FC,
                VALIDATION_MONTH.plusDays(5), true, null, null);
        var selected = new TransactionTemplate(transactionManager).execute(status -> exceptionPayments.scheduleReconciliationRuns(source, scope));
        assertThat(selected).containsExactlyElementsOf(ids);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    void regeneratedScheduleChecksTheEarlierJournalsOwningRunBeforeReplacingIt(boolean finalized) {
        var contract = anyContract();
        Long contractId = ((Number) contract.get("contract_id")).longValue();
        Long actor = jdbcTemplate.queryForObject("SELECT user_id FROM fgc.app_user WHERE login_id='settle01'", Long.class);
        Long originalRun = insertValidationRun(VALIDATION_MONTH);
        Long product = ((Number) contract.get("product_offering_id")).longValue();
        selectContract(originalRun, contractId, product);
        Long policy = anyPolicyVersionId();
        Long item = anyCommissionItemId();
        Long agent = ((Number) contract.get("agent_id")).longValue();
        insertScheduleLine(contractId, "GA_TO_FC", policy, item, agent, VALIDATION_MONTH.plusDays(9));
        journalPostingPort.post(newContext(originalRun, VALIDATION_MONTH));
        Long original = jdbcTemplate.queryForObject("SELECT journal_header_id FROM fgc.journal_header WHERE validation_run_id=?", Long.class, originalRun);
        jdbcTemplate.update("UPDATE fgc.validation_run SET status='RUNNING' WHERE validation_run_id=?", originalRun);
        jdbcTemplate.update("UPDATE fgc.validation_run SET status='COMPLETED',current_step=8 WHERE validation_run_id=?", originalRun);
        if (finalized) jdbcTemplate.update("UPDATE fgc.validation_run SET status='FINALIZED',current_step=10,finalized_at=now() WHERE validation_run_id=?", originalRun);
        Long nextRun = insertValidationRun(VALIDATION_MONTH);
        selectContract(nextRun, contractId, product);
        insertScheduleLine(contractId, "GA_TO_FC", policy, item, agent, VALIDATION_MONTH.plusDays(9));
        var tx = new TransactionTemplate(transactionManager);
        if (finalized) {
            assertThatThrownBy(() -> tx.executeWithoutResult(status -> expectedJournals.postExpectedForContract(contractId, nextRun, VALIDATION_MONTH, actor)))
                    .isInstanceOf(com.susukkang.fgc.common.exception.FgcBusinessException.class);
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM fgc.journal_header WHERE journal_header_id=?", String.class, original)).isEqualTo("POSTED");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.journal_header WHERE validation_run_id=?", Long.class, nextRun)).isZero();
        } else {
            tx.executeWithoutResult(status -> expectedJournals.postExpectedForContract(contractId, nextRun, VALIDATION_MONTH, actor));
            assertThat(jdbcTemplate.queryForObject("SELECT status FROM fgc.journal_header WHERE journal_header_id=?", String.class, original)).isEqualTo("REVERSED");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.journal_header WHERE validation_run_id=? AND status='POSTED'", Long.class, nextRun)).isEqualTo(1);
        }
    }

    @Test
    void postsTwoHundredSourcesWithoutDuplicatesAndMeasuresThePostingCost() {
        var contract = anyContract();
        Long contractId = ((Number) contract.get("contract_id")).longValue();
        Long run = insertValidationRun(VALIDATION_MONTH);
        selectContract(run, contractId, ((Number) contract.get("product_offering_id")).longValue());
        Long first = insertScheduleLine(contractId, "GA_TO_FC", anyPolicyVersionId(), anyCommissionItemId(),
                ((Number) contract.get("agent_id")).longValue(), VALIDATION_MONTH.plusDays(9));
        jdbcTemplate.update("""
                INSERT INTO fgc.schedule_line
                    (schedule_header_id,line_no,installment_no,contract_month_no,due_date,
                     commission_item_id,beneficiary_agent_id,basis_code,basis_amount,
                     calculation_type,rate_pct,expected_amount)
                SELECT sl.schedule_header_id,n,n,n,sl.due_date,sl.commission_item_id,
                       sl.beneficiary_agent_id,sl.basis_code,sl.basis_amount,
                       sl.calculation_type,sl.rate_pct,sl.expected_amount
                FROM fgc.schedule_line sl CROSS JOIN generate_series(2,200) n WHERE sl.schedule_line_id=?
                """, first);
        long started = System.nanoTime();
        var result = journalPostingPort.post(newContext(run, VALIDATION_MONTH));
        long firstMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(result.postedJournalCount()).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM fgc.journal_header "
                + "WHERE validation_run_id=? AND status='POSTED'", Long.class, run)).isEqualTo(200);
        started = System.nanoTime();
        var repeated = journalPostingPort.post(newContext(run, VALIDATION_MONTH));
        long repeatMillis = (System.nanoTime() - started) / 1_000_000;
        assertThat(repeated.postedJournalCount()).isZero();
        assertThat(repeated.skippedCount()).isEqualTo(200);
        System.out.printf("JOURNAL_POST_200 firstMs=%d repeatMs=%d%n", firstMillis, repeatMillis);
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

        JournalPostingResult firstRun = journalPostingPort.post(newContext(validationRunId, VALIDATION_MONTH));
        JournalPostingResult secondRun = journalPostingPort.post(newContext(validationRunId, VALIDATION_MONTH));

        assertThat(firstRun.postedJournalCount()).isEqualTo(1);
        assertThat(firstRun.skippedCount()).isZero();
        // 재실행은 새로 기표한 게 없다 — postedJournalCount는 0이고, 이미 처리된 원천은 skip으로 집계된다
        assertThat(secondRun.postedJournalCount()).isZero();
        assertThat(secondRun.skippedCount()).isEqualTo(1);
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
