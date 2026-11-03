package com.susukkang.fgc.arbitrage.repository;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.transaction.performance.PaymentJdbcObservation;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/** #754: 실제 PostgreSQL 원천에서 동일 의무의 잔여액과 연결 불명확 여부를 검증한다. */
@SpringBootTest
@Import(PaymentJdbcObservation.Config.class)
@Transactional
class ArbitrageRemainingCommissionIntegrationTest {
    private static final LocalDate MONTH = LocalDate.of(2030, 8, 1);
    private static final LocalDate AS_OF = MONTH.plusMonths(1);
    @Autowired ArbitrageCheckRepository repository;
    @Autowired JdbcTemplate jdbc;
    private Long contract;
    private Long header;
    private Long line;
    private Long agent;
    private Long item;

    @BeforeEach
    void fixture() {
        var columns = jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                 WHERE table_schema='fgc' AND table_name='insurance_contract'
                   AND is_identity='NO' AND is_generated='NEVER' ORDER BY ordinal_position
                """, String.class);
        String select = columns.stream().map(c -> switch (c) {
            case "contract_no" -> "'IT-754-" + UUID.randomUUID() + "'";
            case "current_status" -> "'ACTIVE'";
            default -> c;
        }).collect(Collectors.joining(","));
        contract = id("INSERT INTO fgc.insurance_contract (" + String.join(",", columns) + ") SELECT "
                + select + " FROM fgc.insurance_contract ORDER BY contract_id LIMIT 1 RETURNING contract_id");
        agent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id=?", contract);
        item = id("SELECT min(commission_item_id) FROM fgc.commission_item");
        header = id("""
                INSERT INTO fgc.schedule_header(contract_id,payment_stage,policy_version_id,schedule_version_no,
                    schedule_purpose,schedule_regime)
                VALUES (?,'GA_TO_FC',(SELECT min(policy_version_id) FROM fgc.policy_version),1,'OPERATIONAL','CURRENT')
                RETURNING schedule_header_id
                """, contract);
        line = line(agent, 1);
    }

    @Test
    void overdueUnpaidObligationDoesNotDisappear() { assertRemaining("100000"); }

    @Test
    void partialPaymentConsumesOnlyItsAttributedAmount() {
        payment(line, "50000", "CONFIRMED", true);
        assertRemaining("50000");
    }

    @Test
    void fullPaymentLeavesNoRemainingAmount() {
        payment(line, "100000", "CONFIRMED", true);
        assertRemaining("0");
    }

    @Test
    void overpaymentCannotConsumeAnotherInstallment() {
        line(agent, 2);
        payment(line, "120000", "CONFIRMED", true);
        assertRemaining("100000");
    }

    @Test
    void multipleLinkedPaymentsAreAggregatedBeforeJoiningSchedules() {
        payment(line, "30000", "CONFIRMED", true);
        payment(line, "20000", "CONFIRMED", true);
        assertRemaining("50000");
    }

    @Test
    void anotherRecipientAndInstallmentAreNotReduced() {
        Long other = id("SELECT agent_id FROM fgc.agent WHERE agent_id<>? ORDER BY agent_id LIMIT 1", agent);
        line(other, 1);
        line(agent, 2);
        payment(line, "50000", "CONFIRMED", true);
        assertRemaining("250000");
    }

    @Test
    void draftAndFutureAttributedPaymentDoNotConsumeAsOfBalance() {
        payment(line, "50000", "DRAFT", true);
        Long attribution = payment(line, "50000", "DRAFT", true);
        jdbc.update("UPDATE fgc.transaction_attribution SET attribution_date=?,attribution_month=? WHERE transaction_attribution_id=?",
                AS_OF.plusDays(1), AS_OF, attribution);
        confirm(attribution);
        assertRemaining("100000");
    }

    @Test
    void cancellationRestoresBalanceAndConfirmedReplacementConsumesOnce() {
        Long old = payment(line, "50000", "CONFIRMED", true);
        assertRemaining("50000");
        jdbc.update("UPDATE fgc.commission_transaction SET status='CANCELLED' WHERE commission_transaction_id="
                + "(SELECT commission_transaction_id FROM fgc.transaction_attribution WHERE transaction_attribution_id=?)", old);
        assertRemaining("100000");
        payment(line, "100000", "CONFIRMED", true);
        assertRemaining("0");
        assertRemaining("0");
    }

    @Test
    void generalDeductionDoesNotReopenTheOriginalPaymentObligation() {
        payment(line, "50000", "CONFIRMED", true);
        Long deduction = payment(line, "20000", "DRAFT", true);
        jdbc.update("UPDATE fgc.commission_transaction SET cashflow_type='DEDUCTION' WHERE commission_transaction_id="
                + "(SELECT commission_transaction_id FROM fgc.transaction_attribution WHERE transaction_attribution_id=?)", deduction);
        confirm(deduction);
        assertRemaining("50000");
        assertThat(repository.sumConfirmedCommissionAmount(contract, PaymentStage.GA_TO_FC, AS_OF).getPaidCommissionAmount())
                .isEqualByComparingTo("30000");
    }

    @Test
    void holdRemainsButCancelledScheduleDoesNot() {
        jdbc.update("UPDATE fgc.schedule_line SET line_status='HOLD' WHERE schedule_line_id=?", line);
        payment(line, "50000", "CONFIRMED", true);
        assertRemaining("50000");
        jdbc.update("UPDATE fgc.schedule_line SET line_status='CANCELLED' WHERE schedule_line_id=?", line);
        assertRemaining("0");
    }

    @Test
    void unlinkedPaymentIsNotGuessedEvenForOneMonthAndItem() {
        Long attribution = payment(line, "50000", "CONFIRMED", false);
        var summary = repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF);
        assertThat(summary.remainingAmount()).isEqualByComparingTo("100000");
        assertThat(summary.unresolvedAttributionIds()).containsExactly(attribution);
    }

    @Test
    void invalidRecipientInExplicitLinkIsReportedAndNotDeducted() {
        Long attribution = payment(line, "50000", "DRAFT", true);
        Long other = id("SELECT agent_id FROM fgc.agent WHERE agent_id<>? ORDER BY agent_id LIMIT 1", agent);
        jdbc.update("UPDATE fgc.commission_transaction SET recipient_agent_id=? WHERE commission_transaction_id="
                + "(SELECT commission_transaction_id FROM fgc.transaction_attribution WHERE transaction_attribution_id=?)", other, attribution);
        confirm(attribution);
        var summary = repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF);
        assertThat(summary.remainingAmount()).isEqualByComparingTo("100000");
        assertThat(summary.unresolvedAttributionIds()).containsExactly(attribution);
    }

    @Test
    void knownConfirmedScheduleIsNotMisclassifiedAsUnlinkedToAnotherInstallment() {
        Long attribution = payment(line, "50000", "DRAFT", true);
        jdbc.update("UPDATE fgc.commission_transaction SET installment_no=NULL WHERE commission_transaction_id="
                + "(SELECT commission_transaction_id FROM fgc.transaction_attribution WHERE transaction_attribution_id=?)", attribution);
        confirm(attribution);
        jdbc.update("UPDATE fgc.schedule_line SET line_status='CONFIRMED' WHERE schedule_line_id=?", line);
        line(agent, 2);
        var summary = repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF);
        assertThat(summary.remainingAmount()).isEqualByComparingTo("100000");
        assertThat(summary.unresolvedAttributionIds()).isEmpty();
    }

    @Test
    void completedOneToOneReconciliationResolvesUnlinkedPaymentWithoutMultiplyingIt() {
        Long attribution = payment(line, "50000", "CONFIRMED", false);
        reconciliation(line, attribution, false);
        reconciliation(line, attribution, false);
        var summary = repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF);
        assertThat(summary.remainingAmount()).isEqualByComparingTo("50000");
        assertThat(summary.unresolvedAttributionIds()).isEmpty();
    }

    @Test
    void supersededReconciliationCannotResolveAnUnknownLink() {
        Long attribution = payment(line, "50000", "CONFIRMED", false);
        reconciliation(line, attribution, true);
        var summary = repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF);
        assertThat(summary.remainingAmount()).isEqualByComparingTo("100000");
        assertThat(summary.unresolvedAttributionIds()).containsExactly(attribution);
    }

    @Test
    void conflictingExplicitAndReconciledLinksRequireReview() {
        Long attribution = payment(line, "50000", "DRAFT", true);
        Long another = line(agent, 2);
        jdbc.update("UPDATE fgc.commission_transaction SET installment_no=NULL WHERE commission_transaction_id="
                + "(SELECT commission_transaction_id FROM fgc.transaction_attribution WHERE transaction_attribution_id=?)", attribution);
        confirm(attribution);
        reconciliation(another, attribution, false);
        var summary = repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF);
        assertThat(summary.remainingAmount()).isEqualByComparingTo("200000");
        assertThat(summary.unresolvedAttributionIds()).containsExactly(attribution);
    }

    @Test
    void lateUnlinkedPaymentWithKnownInstallmentRequiresReview() {
        Long attribution = latePayment(false);
        assertUnresolved(attribution);
    }

    @Test
    void lateUnlinkedPaymentForAnotherInstallmentDoesNotTriggerReview() {
        latePayment(false, 2);
        assertRemaining("100000");
        assertThat(repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF)
                .unresolvedAttributionIds()).isEmpty();
    }

    @Test
    void inactiveDirectLinkWithMissingInstallmentRequiresReviewAcrossMonths() {
        Long attribution = latePayment(true, null);
        regenerateFixture(1);
        assertUnresolved(attribution);
    }

    @Test
    void inactiveReconciliationLinkWithMissingInstallmentRequiresReviewAcrossMonths() {
        Long attribution = latePayment(false, null);
        reconciliation(line, attribution, false);
        regenerateFixture(1);
        assertUnresolved(attribution);
    }

    @Test
    void currentReconciliationCanResolveHistoricalLinkToSameObligation() {
        Long attribution = latePayment(true, null);
        regenerateFixture(1);
        reconciliation(line, attribution, false);
        var summary = repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF);
        assertThat(summary.remainingAmount()).isEqualByComparingTo("50000");
        assertThat(summary.unresolvedAttributionIds()).isEmpty();
    }

    @Test
    void historicalDirectLinkConflictingWithCurrentReconciliationRequiresReview() {
        Long attribution = latePayment(true, null);
        regenerateFixture(2);
        reconciliation(line, attribution, false);
        assertUnresolved(attribution);
    }

    @Test
    void inactiveLinkToDifferentInstallmentDoesNotFlagUnrelatedRemaining() {
        latePayment(true, null);
        regenerateFixture(2);
        var summary = repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF);
        assertThat(summary.remainingAmount()).isEqualByComparingTo("100000");
        assertThat(summary.unresolvedAttributionIds()).isEmpty();
    }

    @Test
    void inactiveKnownObligationDoesNotFlagAnotherInstallmentEvenInSameMonth() {
        Long attribution = payment(line, "50000", "DRAFT", true);
        clearOrSetInstallment(attribution, null);
        confirm(attribution);
        regenerateFixture(2);
        var summary = repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF);
        assertThat(summary.remainingAmount()).isEqualByComparingTo("100000");
        assertThat(summary.unresolvedAttributionIds()).isEmpty();
    }

    @Test
    void inactiveLinkToAnotherRecipientDoesNotFlagCurrentRecipient() {
        latePayment(true, null);
        Long other = id("SELECT agent_id FROM fgc.agent WHERE agent_id<>? ORDER BY agent_id LIMIT 1", agent);
        agent = other;
        regenerateFixture(1);
        var summary = repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF);
        assertThat(summary.remainingAmount()).isEqualByComparingTo("100000");
        assertThat(summary.unresolvedAttributionIds()).isEmpty();
    }

    @Test
    void conflictingNoneligibleLinksStillExposeSourceDataConflict() {
        Long attribution = payment(line, "50000", "DRAFT", true);
        clearOrSetInstallment(attribution, null);
        confirm(attribution);
        Long another = line(agent, 2);
        reconciliation(another, attribution, false);
        jdbc.update("UPDATE fgc.schedule_line SET line_status='MATCHED' WHERE schedule_header_id=?", header);
        line(agent, 3);
        assertUnresolved(attribution);
    }

    @Test
    void balanceSnapshotReadsPaidDeductionRemainingAndUnresolvedInOneJdbcExecution() {
        payment(line, "30000", "CONFIRMED", true);
        Long unknown = payment(line, "20000", "CONFIRMED", false);
        Long deduction = payment(line, "10000", "DRAFT", true);
        jdbc.update("UPDATE fgc.commission_transaction SET cashflow_type='DEDUCTION' WHERE commission_transaction_id="
                + "(SELECT commission_transaction_id FROM fgc.transaction_attribution WHERE transaction_attribution_id=?)", deduction);
        confirm(deduction);
        PaymentJdbcObservation.Snapshot observation;
        PaymentJdbcObservation.start();
        try {
            var balance = repository.commissionBalanceSnapshot(contract, PaymentStage.GA_TO_FC, AS_OF);
            assertThat(balance.confirmedPaymentAmount()).isEqualByComparingTo("50000");
            assertThat(balance.confirmedDeductionAmount()).isEqualByComparingTo("10000");
            assertThat(balance.planned().remainingAmount()).isEqualByComparingTo("70000");
            assertThat(balance.planned().unresolvedAttributionIds()).containsExactly(unknown);
        } finally {
            observation = PaymentJdbcObservation.stop();
        }
        assertThat(observation.executions()).hasSize(1);
    }

    private Long latePayment(boolean linked) {
        return latePayment(linked, 1);
    }

    private Long latePayment(boolean linked, Integer installment) {
        Long attribution = payment(line, "50000", "DRAFT", linked);
        jdbc.update("UPDATE fgc.transaction_attribution SET attribution_date=?,attribution_month=? WHERE transaction_attribution_id=?",
                AS_OF, AS_OF, attribution);
        clearOrSetInstallment(attribution, installment);
        confirm(attribution);
        return attribution;
    }

    private void clearOrSetInstallment(Long attribution, Integer installment) {
        jdbc.update("UPDATE fgc.commission_transaction SET installment_no=? WHERE commission_transaction_id="
                + "(SELECT commission_transaction_id FROM fgc.transaction_attribution WHERE transaction_attribution_id=?)",
                installment, attribution);
    }

    // 재생성이 보존하는 이전 연결과 새 회차 ID를 DB에 구성한다. 서비스 자체의 재생성 검증과 구분한다.
    private void regenerateFixture(int installment) {
        Long oldHeader = header;
        jdbc.update("UPDATE fgc.schedule_header SET active_yn=false,status='ADJUSTED' WHERE schedule_header_id=?", oldHeader);
        header = id("""
                INSERT INTO fgc.schedule_header(contract_id,payment_stage,policy_version_id,schedule_version_no,
                    schedule_purpose,schedule_regime,regenerated_from_id)
                SELECT contract_id,payment_stage,policy_version_id,2,'OPERATIONAL','CURRENT',schedule_header_id
                  FROM fgc.schedule_header WHERE schedule_header_id=? RETURNING schedule_header_id
                """, oldHeader);
        line = line(agent, installment);
    }

    private void assertUnresolved(Long attribution) {
        var summary = repository.plannedCommissionSummary(contract, PaymentStage.GA_TO_FC, AS_OF);
        assertThat(summary.remainingAmount()).isEqualByComparingTo("100000");
        assertThat(summary.unresolvedAttributionIds()).containsExactly(attribution);
    }

    private Long line(Long beneficiary, int installment) {
        return id("""
                INSERT INTO fgc.schedule_line(schedule_header_id,line_no,installment_no,contract_month_no,due_date,
                    commission_item_id,beneficiary_agent_id,basis_code,basis_amount,calculation_type,fixed_amount,expected_amount)
                SELECT ?,COALESCE(MAX(line_no),0)+1,?,?,?, ?,?,'TEST',100000,'FIXED',100000,100000
                  FROM fgc.schedule_line WHERE schedule_header_id=? RETURNING schedule_line_id
                """, header, installment, installment, MONTH.plusDays(14), item, beneficiary, header);
    }

    private Long payment(Long scheduleLine, String amount, String status, boolean linked) {
        Long transaction = id("""
                INSERT INTO fgc.commission_transaction(payment_stage,source_type,source_business_key,source_contract_id,
                    recipient_agent_id,commission_item_id,installment_no,settlement_month,due_date,amount,cashflow_type,status)
                SELECT 'GA_TO_FC','GA_MANUAL_PAYMENT',?, ?,beneficiary_agent_id,commission_item_id,installment_no,
                    ?,due_date,?,'PAYMENT','DRAFT' FROM fgc.schedule_line WHERE schedule_line_id=?
                RETURNING commission_transaction_id
                """, "IT-754:" + UUID.randomUUID(), contract, MONTH, new BigDecimal(amount), scheduleLine);
        Long attribution = id("""
                INSERT INTO fgc.transaction_attribution(commission_transaction_id,attribution_seq,attribution_scope,
                    contract_id,schedule_line_id,agent_id,attribution_date,attribution_month,attributed_amount,
                    inclusion_status_snapshot,attribution_method,allocation_basis_snapshot,evidence_ref)
                VALUES (?,1,'CONTRACT',?,?,?, ?,?,?,'INCLUDED','DIRECT',
                    '{"inclusionDecisionReason":"direct","allocationBasis":"DIRECT"}'::jsonb,'754')
                RETURNING transaction_attribution_id
                """, transaction, contract, linked ? scheduleLine : null, agent, MONTH.plusDays(14), MONTH, new BigDecimal(amount));
        if (!"DRAFT".equals(status)) jdbc.update("UPDATE fgc.commission_transaction SET status=? WHERE commission_transaction_id=?", status, transaction);
        return attribution;
    }

    private void reconciliation(Long scheduleLine, Long attribution, boolean superseded) {
        Long validation = id("""
                INSERT INTO fgc.validation_run(validation_month,run_no,run_type,status)
                SELECT ?,COALESCE(MAX(run_no),0)+1,'PRE_CONFIRM','CREATED' FROM fgc.validation_run WHERE validation_month=?
                RETURNING validation_run_id
                """, MONTH, MONTH);
        Long run = id("""
                INSERT INTO fgc.reconciliation_run(settlement_month,payment_stage,insurer_id,status,validation_run_id)
                SELECT ?,'GA_TO_FC',insurer_id,'CREATED',? FROM fgc.insurance_contract WHERE contract_id=?
                RETURNING reconciliation_run_id
                """, MONTH, validation, contract);
        jdbc.update("UPDATE fgc.reconciliation_run SET status='RUNNING',started_at=clock_timestamp() WHERE reconciliation_run_id=?", run);
        Long result = id("""
                INSERT INTO fgc.reconciliation_result(reconciliation_run_id,match_group_key,contract_id,
                    commission_item_id,installment_no,expected_agent_id,actual_agent_id,result_type,
                    expected_total_amount,actual_total_amount,difference_amount)
                VALUES (?,?,?, ?,1,?,?,'AMOUNT_DIFFERENCE',100000,50000,50000) RETURNING reconciliation_result_id
                """, run, "IT-754:" + UUID.randomUUID(), contract, item, agent, agent);
        jdbc.update("""
                INSERT INTO fgc.reconciliation_match(reconciliation_result_id,match_seq,schedule_line_id,
                    transaction_attribution_id,matched_amount,match_role)
                VALUES (?,1,?,NULL,100000,'EXPECTED'), (?,2,NULL,?,50000,'ACTUAL')
                """, result, scheduleLine, result, attribution);
        jdbc.update("UPDATE fgc.reconciliation_run SET status='COMPLETED',superseded_yn=? WHERE reconciliation_run_id=?", superseded, run);
    }

    private void confirm(Long attribution) {
        jdbc.update("UPDATE fgc.commission_transaction SET status='CONFIRMED' WHERE commission_transaction_id="
                + "(SELECT commission_transaction_id FROM fgc.transaction_attribution WHERE transaction_attribution_id=?)", attribution);
    }

    private Long id(String sql, Object... args) { return jdbc.queryForObject(sql, Long.class, args); }

    private void assertRemaining(String expected) {
        assertThat(repository.sumPlannedCommissionAmount(contract, PaymentStage.GA_TO_FC, AS_OF))
                .isEqualByComparingTo(expected);
    }
}
