package com.susukkang.fgc.journal.repository;

import com.susukkang.fgc.journal.dto.ScheduleJournalSourceRow;
import com.susukkang.fgc.journal.dto.TransactionJournalSourceRow;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 원천 선별 조건과 PostgreSQL 네이티브 DTO 매핑을 직접 생성한 데이터로 검증한다. */
@SpringBootTest
@Transactional
class JournalPostingSourceQueryRepositoryIntegrationTest {

    private static final LocalDate MONTH = LocalDate.of(2094, 4, 1);

    @Autowired
    private JournalPostingSourceQueryRepository repository;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final String keyPrefix = "TEST-374-SOURCE-" + System.nanoTime() + "-";
    private Long contractId;
    private Long productOfferingId;
    private Long agentId;
    private Long policyVersionId;
    private Long commissionItemId;
    private Long validationRunId;
    private int transactionSequence;
    private int validationRunSequence;

    @BeforeEach
    void setUp() {
        Map<String, Object> contract = jdbcTemplate.queryForMap("""
                SELECT contract_id, product_offering_id, agent_id
                  FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1
                """);
        contractId = ((Number) contract.get("contract_id")).longValue();
        productOfferingId = ((Number) contract.get("product_offering_id")).longValue();
        agentId = ((Number) contract.get("agent_id")).longValue();
        policyVersionId = jdbcTemplate.queryForObject("""
                SELECT policy_version_id FROM fgc.policy_version
                 WHERE status = 'ACTIVE' ORDER BY policy_version_id LIMIT 1
                """, Long.class);
        commissionItemId = jdbcTemplate.queryForObject("""
                SELECT commission_item_id FROM fgc.commission_item
                 ORDER BY commission_item_id LIMIT 1
                """, Long.class);
        validationRunId = insertValidationRun("SELECTED");
        // 기존 운영 스케줄은 테스트 트랜잭션 안에서만 비활성화되고 종료 시 복원된다.
        jdbcTemplate.update("""
                UPDATE fgc.schedule_header SET active_yn = false
                 WHERE contract_id = ? AND schedule_purpose = 'OPERATIONAL' AND active_yn = true
                """, contractId);
    }

    @Test
    void scheduleSourcesMapAllFieldsAndExcludeOtherMonthsBlockedLinesAndMissingRecipients() {
        Long incomeHeader = insertScheduleHeader("INSURER_TO_GA", "PLANNED", true, "OPERATIONAL");
        Long incomeFirst = insertScheduleLine(incomeHeader, 1, MONTH.plusDays(9), "PLANNED", agentId);
        Long incomeSecond = insertScheduleLine(incomeHeader, 2, MONTH.plusDays(19), "CONFIRMED", null);
        insertScheduleLine(incomeHeader, 3, MONTH.plusMonths(1), "PLANNED", null);
        insertScheduleLine(incomeHeader, 4, MONTH, "HOLD", null);
        insertScheduleLine(incomeHeader, 5, MONTH, "CANCELLED", null);
        Long payoutHeader = insertScheduleHeader("GA_TO_FC", "PLANNED", true, "OPERATIONAL");
        Long payoutFirst = insertScheduleLine(payoutHeader, 1, MONTH.plusDays(9), "PLANNED", agentId);
        Long payoutSecond = insertScheduleLine(payoutHeader, 2, MONTH.plusDays(19), "RESTARTED", agentId);
        insertScheduleLine(payoutHeader, 3, MONTH, "PLANNED", null);
        insertScheduleLine(payoutHeader, 4, MONTH, "HOLD", agentId);
        insertScheduleLine(payoutHeader, 5, MONTH, "CANCELLED", agentId);
        insertScheduleLine(payoutHeader, 6, MONTH.plusMonths(1), "PLANNED", agentId);

        List<ScheduleJournalSourceRow> income = repository.findExpectedInsurerIncomeSources(validationRunId, MONTH);
        List<ScheduleJournalSourceRow> payout = repository.findExpectedFcPayoutSources(validationRunId, MONTH);

        assertThat(income).extracting(ScheduleJournalSourceRow::getScheduleLineId)
                .containsExactly(incomeFirst, incomeSecond);
        assertThat(payout).extracting(ScheduleJournalSourceRow::getScheduleLineId)
                .containsExactly(payoutFirst, payoutSecond);
        assertScheduleSource(income.get(0), incomeFirst, null, MONTH.plusDays(9), "예상 수입 스케줄 1회차");
        assertScheduleSource(income.get(1), incomeSecond, null, MONTH.plusDays(19), "예상 수입 스케줄 2회차");
        assertScheduleSource(payout.get(0), payoutFirst, agentId, MONTH.plusDays(9), "예상 지급 스케줄 1회차");
        assertScheduleSource(payout.get(1), payoutSecond, agentId, MONTH.plusDays(19), "예상 지급 스케줄 2회차");
    }

    @Test
    void scheduleSourcesExcludeInactiveComparisonAndBlockedHeaders() {
        for (String stage : List.of("INSURER_TO_GA", "GA_TO_FC")) {
            Long inactive = insertScheduleHeader(stage, "PLANNED", false, "OPERATIONAL");
            insertScheduleLine(inactive, 1, MONTH, "PLANNED", agentId);
            Long comparison = insertScheduleHeader(stage, "PLANNED", true, "COMPARISON");
            insertScheduleLine(comparison, 1, MONTH, "PLANNED", agentId);
            Long blocked = insertScheduleHeader(stage, "HOLD", true, "OPERATIONAL");
            insertScheduleLine(blocked, 1, MONTH, "PLANNED", agentId);
        }

        assertThat(repository.findExpectedInsurerIncomeSources(validationRunId, MONTH)).isEmpty();
        assertThat(repository.findExpectedFcPayoutSources(validationRunId, MONTH)).isEmpty();

        jdbcTemplate.update("""
                UPDATE fgc.schedule_header SET status = 'CANCELLED'
                 WHERE contract_id = ? AND active_yn = true AND schedule_purpose = 'OPERATIONAL'
                """, contractId);
        assertThat(repository.findExpectedInsurerIncomeSources(validationRunId, MONTH)).isEmpty();
        assertThat(repository.findExpectedFcPayoutSources(validationRunId, MONTH)).isEmpty();
    }

    @Test
    void transactionSourcesMapAllFieldsAndUseAttributionContractAndDateFallbacks() {
        Long paid = insertTransaction("INSURER_TO_GA", "INSURER_STATEMENT", "PAYMENT", "CONFIRMED",
                MONTH, MONTH.plusDays(4), MONTH.plusDays(9), 1);
        Long due = insertTransaction("INSURER_TO_GA", "GA_MANUAL_PAYMENT", "PAYMENT", "CONFIRMED",
                MONTH, MONTH.plusDays(4), null, 1);
        Long month = insertTransaction("INSURER_TO_GA", "INSURER_STATEMENT", "PAYMENT", "CONFIRMED",
                MONTH, null, null, 1);
        Long manualPayout = insertTransaction("GA_TO_FC", "GA_MANUAL_PAYMENT", "PAYMENT", "CONFIRMED",
                MONTH, null, null, 1);
        Long confirmedPayout = insertTransaction("GA_TO_FC", "GA_CONFIRMED_PAYMENT", "PAYMENT", "CONFIRMED",
                MONTH, MONTH.plusDays(4), MONTH.plusDays(9), 1);

        List<TransactionJournalSourceRow> income = repository.findActualInsurerStatementSources(validationRunId, MONTH);
        List<TransactionJournalSourceRow> payout = repository.findConfirmedFcPayoutSources(validationRunId, MONTH);

        assertThat(income).extracting(TransactionJournalSourceRow::getCommissionTransactionId)
                .containsExactly(paid, due, month);
        assertThat(income).extracting(TransactionJournalSourceRow::getJournalDate)
                .containsExactly(MONTH.plusDays(9), MONTH.plusDays(4), MONTH);
        assertThat(payout).extracting(TransactionJournalSourceRow::getCommissionTransactionId)
                .containsExactly(manualPayout, confirmedPayout);
        assertTransactionSource(income.get(0), paid, null, MONTH.plusDays(9), "실제 명세 " + keyPrefix + "1");
        assertTransactionSource(income.get(1), due, null, MONTH.plusDays(4), "실제 명세 " + keyPrefix + "2");
        assertTransactionSource(income.get(2), month, null, MONTH, "실제 명세 " + keyPrefix + "3");
        assertTransactionSource(payout.get(0), manualPayout, agentId, MONTH, "확정 지급 건 " + keyPrefix + "4");
        assertTransactionSource(payout.get(1), confirmedPayout, agentId, MONTH.plusDays(9), "확정 지급 건 " + keyPrefix + "5");
    }

    @Test
    void transactionSourcesExcludeDraftCancelledDeductionsAdjustmentsOtherMonthsAndMultipleAttributions() {
        for (String stage : List.of("INSURER_TO_GA", "GA_TO_FC")) {
            String sourceType = stage.equals("INSURER_TO_GA") ? "INSURER_STATEMENT" : "GA_CONFIRMED_PAYMENT";
            insertTransaction(stage, sourceType, "PAYMENT", "DRAFT", MONTH, null, null, 1);
            insertTransaction(stage, sourceType, "PAYMENT", "CANCELLED", MONTH, null, null, 1);
            insertTransaction(stage, sourceType, "DEDUCTION", "CONFIRMED", MONTH, null, null, 1);
            insertTransaction(stage, "ADJUSTMENT", "PAYMENT", "CONFIRMED", MONTH, null, null, 1);
            insertTransaction(stage, sourceType, "PAYMENT", "CONFIRMED", MONTH.plusMonths(1), null, null, 1);
            insertTransaction(stage, sourceType, "PAYMENT", "CONFIRMED", MONTH, null, null, 2);
        }

        assertThat(repository.findActualInsurerStatementSources(validationRunId, MONTH)).isEmpty();
        assertThat(repository.findConfirmedFcPayoutSources(validationRunId, MONTH)).isEmpty();
    }

    @Test
    void sourcesAreEmptyOutsideSelectedValidationRunAndMonth() {
        Long incomeHeader = insertScheduleHeader("INSURER_TO_GA", "PLANNED", true, "OPERATIONAL");
        Long payoutHeader = insertScheduleHeader("GA_TO_FC", "PLANNED", true, "OPERATIONAL");
        insertScheduleLine(incomeHeader, 1, MONTH, "PLANNED", null);
        insertScheduleLine(payoutHeader, 1, MONTH, "PLANNED", agentId);
        insertTransaction("INSURER_TO_GA", "INSURER_STATEMENT", "PAYMENT", "CONFIRMED", MONTH, null, null, 1);
        insertTransaction("GA_TO_FC", "GA_CONFIRMED_PAYMENT", "PAYMENT", "CONFIRMED", MONTH, null, null, 1);

        assertAllSourcesEmpty(insertValidationRun("EXCLUDED"), MONTH);
        assertAllSourcesEmpty(insertValidationRun("REVIEW_REQUIRED"), MONTH);
        assertAllSourcesEmpty(-1L, MONTH);
        assertAllSourcesEmpty(validationRunId, MONTH.plusMonths(1));
    }

    private void assertAllSourcesEmpty(Long runId, LocalDate month) {
        assertThat(repository.findExpectedInsurerIncomeSources(runId, month)).isEmpty();
        assertThat(repository.findExpectedFcPayoutSources(runId, month)).isEmpty();
        assertThat(repository.findActualInsurerStatementSources(runId, month)).isEmpty();
        assertThat(repository.findConfirmedFcPayoutSources(runId, month)).isEmpty();
    }

    private void assertScheduleSource(ScheduleJournalSourceRow row, Long id, Long beneficiaryId,
                                      LocalDate journalDate, String description) {
        assertThat(row.getScheduleLineId()).isEqualTo(id);
        assertThat(row.getContractId()).isEqualTo(contractId);
        assertThat(row.getPolicyVersionId()).isEqualTo(policyVersionId);
        assertThat(row.getCommissionItemId()).isEqualTo(commissionItemId);
        assertThat(row.getBeneficiaryAgentId()).isEqualTo(beneficiaryId);
        assertThat(row.getJournalDate()).isEqualTo(journalDate);
        assertThat(row.getAmount()).isEqualByComparingTo("12345.67");
        assertThat(row.getDescription()).isEqualTo(description);
    }

    private void assertTransactionSource(TransactionJournalSourceRow row, Long id, Long beneficiaryId,
                                         LocalDate journalDate, String description) {
        assertThat(row.getCommissionTransactionId()).isEqualTo(id);
        assertThat(row.getContractId()).isEqualTo(contractId);
        assertThat(row.getPolicyVersionId()).isNull();
        assertThat(row.getCommissionItemId()).isEqualTo(commissionItemId);
        assertThat(row.getBeneficiaryAgentId()).isEqualTo(beneficiaryId);
        assertThat(row.getJournalDate()).isEqualTo(journalDate);
        assertThat(row.getAmount()).isEqualByComparingTo("40000.50");
        assertThat(row.getDescription()).isEqualTo(description);
    }

    private Long insertValidationRun(String selectionStatus) {
        // CREATED 상태의 월 실행은 월당 한 건만 허용된다.
        LocalDate runMonth = MONTH.plusMonths(validationRunSequence++);
        Long runId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run (validation_month, run_no, run_type, status)
                SELECT ?, COALESCE(MAX(run_no), 0) + 1, 'MONTHLY', 'CREATED'
                  FROM fgc.validation_run WHERE validation_month = ?
                RETURNING validation_run_id
                """, Long.class, runMonth, runMonth);
        jdbcTemplate.update("""
                INSERT INTO fgc.validation_target
                    (validation_run_id, contract_id, product_offering_id, selection_status, selection_reason)
                VALUES (?, ?, ?, ?, 'TEST-374')
                """, runId, contractId, productOfferingId, selectionStatus);
        return runId;
    }

    private Long insertScheduleHeader(String stage, String status, boolean active, String purpose) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header
                    (contract_id, payment_stage, policy_version_id, schedule_version_no,
                     schedule_regime, schedule_purpose, scenario_code, status, active_yn)
                SELECT ?, ?, ?, COALESCE(MAX(schedule_version_no), 0) + 1,
                       'CURRENT', ?, ?, ?, ?
                  FROM fgc.schedule_header WHERE contract_id = ? AND payment_stage = ?
                RETURNING schedule_header_id
                """, Long.class, contractId, stage, policyVersionId, purpose,
                purpose.equals("OPERATIONAL") ? null : keyPrefix, status, active, contractId, stage);
    }

    private Long insertScheduleLine(Long headerId, int lineNo, LocalDate dueDate, String status, Long beneficiaryId) {
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_line
                    (schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                     commission_item_id, beneficiary_agent_id, basis_code, basis_amount,
                     calculation_type, fixed_amount, expected_amount, line_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, 'TEST_AMOUNT', 12345.67, 'FIXED', 12345.67, 12345.67, ?)
                RETURNING schedule_line_id
                """, Long.class, headerId, lineNo, lineNo, lineNo, dueDate, commissionItemId, beneficiaryId, status);
    }

    private Long insertTransaction(String stage, String sourceType, String cashflowType, String status,
                                   LocalDate month, LocalDate dueDate, LocalDate paidOn, int attributionCount) {
        int sequence = ++transactionSequence;
        // source_contract_id와 policy_version_id를 비워 귀속행 연결과 nullable DTO 매핑을 확인한다.
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction
                    (payment_stage, source_type, source_business_key,
                     recipient_agent_id, commission_item_id, settlement_month, due_date, paid_on,
                     amount, cashflow_type)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 40000.50, ?)
                RETURNING commission_transaction_id
                """, Long.class, stage, sourceType, keyPrefix + sequence,
                stage.equals("GA_TO_FC") ? agentId : null, commissionItemId, month, dueDate, paidOn, cashflowType);
        BigDecimal attributedAmount = new BigDecimal("40000.50").divide(BigDecimal.valueOf(attributionCount));
        for (int i = 1; i <= attributionCount; i++) {
            jdbcTemplate.update("""
                    INSERT INTO fgc.transaction_attribution
                        (commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                         attribution_date, attribution_month, attributed_amount,
                         inclusion_status_snapshot, attribution_method)
                    VALUES (?, ?, 'CONTRACT', ?, ?, ?, ?, 'INCLUDED', 'DIRECT')
                    """, transactionId, i, contractId, month, month, attributedAmount);
        }
        if (!status.equals("DRAFT")) {
            jdbcTemplate.update("""
                    UPDATE fgc.commission_transaction SET status = 'CONFIRMED'
                     WHERE commission_transaction_id = ?
                    """, transactionId);
            if (status.equals("CANCELLED")) {
                jdbcTemplate.update("""
                        UPDATE fgc.commission_transaction SET status = 'CANCELLED'
                         WHERE commission_transaction_id = ?
                        """, transactionId);
            }
        }
        return transactionId;
    }
}
