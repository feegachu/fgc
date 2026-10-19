package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.code.*;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.journal.dto.*;
import com.susukkang.fgc.journal.domain.*;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.service.*;
import com.susukkang.fgc.transaction.service.CommissionPaymentService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:tc:postgresql:17:///fgc_payment_correction?currentSchema=fgc")
@Transactional
@WithMockUser(roles = "GA_ADMIN")
class PaymentJournalCorrectionIntegrationTest {
    private static final LocalDate TEST_MONTH = LocalDate.of(2087, 11, 1);
    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired JournalCorrectionService corrections;
    @Autowired CommissionPaymentService payments;
    @Autowired ReconciliationExecutionService execution;
    @Autowired ReconciliationExceptionService detection;
    @Autowired com.susukkang.fgc.reconciliation.repository.ReconciliationRunHistoryRepository history;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @Autowired com.susukkang.fgc.validation.batch.contract.JournalPostingPort posting;
    private Long contractId;
    private Long validationRunId;

    @Test
    void repostCancelsOldPaymentAndMatchesReplacementWithoutDuplicateAndResolvesException() {
        Fixture f = fixture();
        var result = corrections.reverseAndRepost(command(f, "100000"));
        assertThat(payments.get(f.payment()).status()).isEqualTo(CommissionPaymentStatus.CANCELLED);
        Long replacement = id("SELECT replacement_payment_id FROM fgc.payment_journal_correction WHERE original_payment_id=?", f.payment());
        assertThat(payments.get(replacement).status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(payments.get(replacement).amount()).isEqualByComparingTo("100000");
        assertThat(jdbcTemplate.queryForMap("SELECT source_entity_id, revision_no, status FROM fgc.journal_header WHERE journal_header_id=?",
                result.repostedJournalHeaderId())).containsEntry("source_entity_id", replacement.toString())
                .containsEntry("revision_no", 1).containsEntry("status", "POSTED");
        Long current = id("SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE corrects_reconciliation_run_id=?", f.run());
        assertThat(resultType(current)).isEqualTo("MATCHED");
        assertThat(resultType(f.run())).isEqualTo("AMOUNT_DIFFERENCE");
        assertThat(jdbcTemplate.queryForObject("SELECT superseded_yn FROM fgc.reconciliation_run WHERE reconciliation_run_id=?", Boolean.class, f.run())).isTrue();
        var versions = history.search(TEST_MONTH,"GA_TO_FC",null,"desc",0,100);
        assertThat(versions.stream().filter(v -> v.getReconciliationRunId().equals(f.run())).findFirst().orElseThrow().isSuperseded()).isTrue();
        assertThat(versions.stream().filter(v -> v.getReconciliationRunId().equals(current)).findFirst().orElseThrow().getCorrectsReconciliationRunId()).isEqualTo(f.run());
        assertThat(status(f.exception())).isEqualTo("RESOLVED");
        assertThat(id("SELECT COUNT(*) FROM fgc.exception_action WHERE exception_case_id=?", f.exception())).isEqualTo(2);
        assertThat(id("SELECT COUNT(*) FROM fgc.commission_transaction WHERE source_contract_id=? AND status='CONFIRMED' AND payment_stage='GA_TO_FC' AND settlement_month=?", contractId, TEST_MONTH)).isEqualTo(1);
        // 원천 키 재시도는 대체 지급 revision 1을 재사용한다.
        assertThat(id("SELECT COUNT(*) FROM fgc.journal_header WHERE journal_type='CONFIRMED_FC_PAYOUT' AND source_entity_type='COMMISSION_TRANSACTION' AND source_entity_id=? AND revision_no=1", replacement.toString())).isEqualTo(1);
        var context = new com.susukkang.fgc.validation.batch.contract.ValidationStepContext(validationRunId,
                new com.susukkang.fgc.validation.batch.contract.ValidationJobContext(TEST_MONTH,1,
                        ValidationRunType.MONTHLY,1,"548-rerun"));
        posting.post(context);
        posting.post(context);
        assertThat(id("SELECT COUNT(*) FROM fgc.journal_header WHERE journal_type='CONFIRMED_FC_PAYOUT' AND source_entity_id=?",replacement.toString())).isEqualTo(1);
        assertThatThrownBy(() -> corrections.reverseAndRepost(command(f, "100000"))).isInstanceOf(FgcBusinessException.class);
        assertThat(id("SELECT COUNT(*) FROM fgc.payment_journal_correction WHERE original_payment_id=?", f.payment())).isEqualTo(1);
    }

    @Test
    void stillWrongAmountDoesNotResolveOriginalException() {
        Fixture f = fixture();
        corrections.reverseAndRepost(command(f, "70000"));
        Long current = id("SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE corrects_reconciliation_run_id=?", f.run());
        assertThat(resultType(current)).isEqualTo("AMOUNT_DIFFERENCE");
        assertThat(status(f.exception())).isEqualTo("NEW");
    }

    @Test
    void reverseOnlyCancelsPaymentAndRetainsUnresolvedMismatch() {
        Fixture f = fixture();
        corrections.reverse(new ReverseJournalCommand(f.journal(), "지급 취소", "548", 1L));
        assertThat(payments.get(f.payment()).status()).isEqualTo(CommissionPaymentStatus.CANCELLED);
        assertThat(id("SELECT replacement_payment_id FROM fgc.payment_journal_correction WHERE original_payment_id=?", f.payment())).isNull();
        Long current = id("SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE corrects_reconciliation_run_id=?", f.run());
        assertThat(resultType(current)).isEqualTo("ACTUAL_MISSING");
        assertThat(status(f.exception())).isEqualTo("NEW");
    }

    @Test
    void changedBusinessDimensionsAreRejectedBeforeCancellingPayment() {
        Fixture f = fixture();
        var cmd = command(f, "100000");
        var lines = cmd.correctedDraft().getLines().stream().map(l -> l.toBuilder().agentId(99L).build()).toList();
        var bad = new ReverseAndRepostJournalCommand(cmd.reversal(), cmd.correctedDraft().toBuilder().lines(lines).build());
        assertThatThrownBy(() -> corrections.reverseAndRepost(bad)).isInstanceOf(FgcBusinessException.class);
        assertThat(payments.get(f.payment()).status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fgc.journal_header WHERE journal_header_id=?", String.class, f.journal())).isEqualTo("POSTED");
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void failedReplacementRollsBackCancellationJournalAndReconciliationTogether() {
        Fixture f = transactions.execute(tx -> fixture());
        var cmd = command(f, "1000000000");
        assertThatThrownBy(() -> transactions.execute(tx -> {
            jdbcTemplate.update("UPDATE fgc.insurance_contract SET contract_date=DATE '2087-11-01' WHERE contract_id=?",contractId);
            return corrections.reverseAndRepost(cmd);
        })).isInstanceOf(com.susukkang.fgc.transaction.service.CommissionPaymentConfirmationRejectedException.class);
        assertThat(payments.get(f.payment()).status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fgc.journal_header WHERE journal_header_id=?",String.class,f.journal())).isEqualTo("POSTED");
        assertThat(id("SELECT COUNT(*) FROM fgc.payment_journal_correction WHERE original_payment_id=?",f.payment())).isZero();
        assertThat(id("SELECT COUNT(*) FROM fgc.journal_correction_group WHERE original_journal_header_id=?",f.journal())).isZero();
        assertThat(jdbcTemplate.queryForObject("SELECT superseded_yn FROM fgc.reconciliation_run WHERE reconciliation_run_id=?",Boolean.class,f.run())).isFalse();
        assertThat(status(f.exception())).isEqualTo("NEW");
    }

    @Test
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void concurrentCorrectionsCreateExactlyOneReplacement() throws Exception {
        Fixture f = transactions.execute(tx -> fixture());
        var cmd = command(f, "100000");
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = new org.springframework.security.concurrent.DelegatingSecurityContextExecutorService(
                java.util.concurrent.Executors.newFixedThreadPool(2));
        java.util.concurrent.Callable<Boolean> task = () -> {
            ready.countDown();
            if (!start.await(20, java.util.concurrent.TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
            try { corrections.reverseAndRepost(cmd); return true; }
            catch (FgcBusinessException conflict) { return false; }
        };
        try {
            var first = pool.submit(task);
            var second = pool.submit(task);
            assertThat(ready.await(20, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(60,java.util.concurrent.TimeUnit.SECONDS),second.get(60,java.util.concurrent.TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true,false);
            assertThat(id("SELECT COUNT(*) FROM fgc.payment_journal_correction WHERE original_payment_id=?",f.payment())).isEqualTo(1);
            assertThat(payments.get(f.payment()).status()).isEqualTo(CommissionPaymentStatus.CANCELLED);
        } finally { start.countDown(); pool.shutdownNow(); }
    }

    @Test
    void finalizedValidationPreventsPaymentCancellation() {
        Fixture f = fixture();
        jdbcTemplate.update("UPDATE fgc.validation_run SET status='FINALIZED',current_step=10,finalized_at=clock_timestamp() WHERE validation_run_id=?",validationRunId);
        assertThatThrownBy(() -> corrections.reverseAndRepost(command(f,"100000"))).isInstanceOf(FgcBusinessException.class);
        assertThat(payments.get(f.payment()).status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
    }

    private Fixture fixture() {
        contractId = cloneContract();
        Long policy = id("SELECT policy_version_id FROM fgc.policy_version WHERE policy_code='REG-CAP-GA-2026-V1' AND status='ACTIVE'");
        Long item = id("SELECT commission_item_id FROM fgc.commission_item WHERE item_code='BASE_COMMISSION'");
        Long insurer = id("SELECT insurer_id FROM fgc.insurance_contract WHERE contract_id=?", contractId);
        Long agent = id("SELECT agent_id FROM fgc.insurance_contract WHERE contract_id=?", contractId);
        validationRunId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.validation_run(validation_month,run_no,status)
                SELECT ?,COALESCE(MAX(run_no),0)+1,'CREATED' FROM fgc.validation_run WHERE validation_month=?
                RETURNING validation_run_id
                """,Long.class,TEST_MONTH,TEST_MONTH);
        Long line = insertSchedule(contractId,policy,item,agent,1,"100000");
        insertPostedJournal("EXPECTED_FC_PAYOUT","SCHEDULE_LINE",line,contractId,agent,item,"100000","EXPECTED_PAYOUT_EXPENSE","EXPECTED_PAYOUT_PAYABLE");
        var old = insertPayment(contractId,policy,item,agent,1,"50000","DRAFT",UUID.randomUUID().toString());
        jdbcTemplate.update("UPDATE fgc.transaction_attribution SET allocation_basis_snapshot='{\"inclusionDecisionReason\":\"direct\",\"allocationBasis\":\"DIRECT\"}'::jsonb,evidence_ref='548' WHERE transaction_attribution_id=?", old.attributionId());
        jdbcTemplate.update("UPDATE fgc.commission_transaction SET status='CONFIRMED' WHERE commission_transaction_id=?",old.transactionId());
        Long journal = insertPostedJournal("CONFIRMED_FC_PAYOUT","COMMISSION_TRANSACTION",old.transactionId(),contractId,agent,item,"50000","CONFIRMED_PAYOUT_EXPENSE","CONFIRMED_PAYOUT_PAYABLE");
        Long run = jdbcTemplate.queryForObject("INSERT INTO fgc.reconciliation_run(settlement_month,payment_stage,insurer_id,validation_run_id) VALUES (?,'GA_TO_FC',?,?) RETURNING reconciliation_run_id",Long.class,TEST_MONTH,insurer,validationRunId);
        jdbcTemplate.update("UPDATE fgc.reconciliation_run SET status='RUNNING',started_at=clock_timestamp() WHERE reconciliation_run_id=?",run);
        execution.executeInTransaction(new ReconciliationExecutionRequest(run,validationRunId,TEST_MONTH,PaymentStage.GA_TO_FC,insurer,1L));
        detection.bulkCreate(run);
        Long exception = id("SELECT e.exception_case_id FROM fgc.exception_case e JOIN fgc.reconciliation_result r ON e.source_entity_id=CAST(r.reconciliation_result_id AS varchar) WHERE r.reconciliation_run_id=? AND r.contract_id=? AND e.exception_type='RECONCILIATION_MISMATCH'",run,contractId);
        jdbcTemplate.update("UPDATE fgc.validation_run SET status='RUNNING' WHERE validation_run_id=?", validationRunId);
        jdbcTemplate.update("UPDATE fgc.validation_run SET status='COMPLETED',current_step=8 WHERE validation_run_id=?", validationRunId);
        jdbcTemplate.update("INSERT INTO fgc.validation_target(validation_run_id,contract_id,product_offering_id,selection_status) SELECT ?,contract_id,product_offering_id,'SELECTED' FROM fgc.insurance_contract WHERE contract_id=?", validationRunId,contractId);
        return new Fixture(old.transactionId(),journal,run,exception,agent,item);
    }

    private ReverseAndRepostJournalCommand command(Fixture f,String amount) {
        BigDecimal value = new BigDecimal(amount);
        var lines = List.of(JournalLineDraft.builder().lineNo(1).accountCode(JournalAccountCode.CONFIRMED_PAYOUT_EXPENSE).debitAmount(value).creditAmount(BigDecimal.ZERO).contractId(contractId).agentId(f.agent()).commissionItemId(f.item()).paymentStage(PaymentStage.GA_TO_FC).build(),
                JournalLineDraft.builder().lineNo(2).accountCode(JournalAccountCode.CONFIRMED_PAYOUT_PAYABLE).debitAmount(BigDecimal.ZERO).creditAmount(value).contractId(contractId).agentId(f.agent()).commissionItemId(f.item()).paymentStage(PaymentStage.GA_TO_FC).build());
        return new ReverseAndRepostJournalCommand(new ReverseJournalCommand(f.journal(),"금액 정정","548",1L),
                JournalHeaderDraft.builder().journalType(JournalType.CONFIRMED_FC_PAYOUT).journalDate(TEST_MONTH.plusDays(14)).sourceEntityType("COMMISSION_TRANSACTION").sourceEntityId(f.payment().toString()).revisionNo(2).validationRunId(validationRunId).contractId(contractId).description("정정").lines(lines).build());
    }
    private String status(Long id) { return jdbcTemplate.queryForObject("SELECT status FROM fgc.exception_case WHERE exception_case_id=?",String.class,id); }
    private String resultType(Long run) { return jdbcTemplate.queryForObject("SELECT result_type FROM fgc.reconciliation_result WHERE reconciliation_run_id=? AND contract_id=?",String.class,run,contractId); }
    private record Fixture(Long payment,Long journal,Long run,Long exception,Long agent,Long item) {}
    private Long cloneContract() {
        var columns = jdbcTemplate.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema='fgc' AND table_name='insurance_contract'
                  AND is_identity='NO' AND is_generated='NEVER'
                ORDER BY ordinal_position
                """, String.class);
        String select = columns.stream().map(column -> switch (column) {
            case "contract_no" -> "'IT-548-" + UUID.randomUUID() + "'";
            case "contract_date" -> "DATE '2026-06-30'";
            case "current_status" -> "'ACTIVE'";
            default -> column;
        }).collect(java.util.stream.Collectors.joining(","));
        return id("INSERT INTO fgc.insurance_contract (" + String.join(",", columns)
                + ") SELECT " + select + " FROM fgc.insurance_contract WHERE contract_id=1 RETURNING contract_id");
    }

    private Long insertSchedule(
            Long contractId,
            Long policyVersionId,
            Long commissionItemId,
            Long agentId,
            int installmentNo,
            String amount
    ) {
        return insertSchedule(
                contractId, policyVersionId, commissionItemId, agentId,
                installmentNo, amount, "OPERATIONAL", null, true, 300 + installmentNo);
    }

    private Long insertSchedule(
            Long contractId,
            Long policyVersionId,
            Long commissionItemId,
            Long agentId,
            int installmentNo,
            String amount,
            String schedulePurpose,
            String scenarioCode,
            boolean active,
            int scheduleVersionNo
    ) {
        Long headerId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_header (
                    contract_id, payment_stage, policy_version_id, schedule_version_no,
                    schedule_purpose, scenario_code, schedule_regime, active_yn
                ) VALUES (?, 'GA_TO_FC', ?, ?, ?, ?, 'CURRENT', ?)
                RETURNING schedule_header_id
                """, Long.class,
                contractId, policyVersionId, scheduleVersionNo,
                schedulePurpose, scenarioCode, active);
        return jdbcTemplate.queryForObject("""
                INSERT INTO fgc.schedule_line (
                    schedule_header_id, line_no, installment_no, contract_month_no, due_date,
                    commission_item_id, beneficiary_agent_id, basis_code, basis_amount,
                    calculation_type, fixed_amount, expected_amount
                ) VALUES (?, 1, ?, ?, ?, ?, ?, 'IT_RECONCILIATION', ?, 'FIXED', ?, ?)
                RETURNING schedule_line_id
                """, Long.class,
                headerId, installmentNo, installmentNo, TEST_MONTH.plusDays(14),
                commissionItemId, agentId,
                new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount));
    }

    private PaymentInsert insertPayment(Long contractId, Long policyVersionId, Long commissionItemId,
                                        Long agentId, Integer installmentNo, String amount,
                                        String status, String suffix) {
        String paymentStage = "GA_TO_FC";
        String sourceType = "GA_MANUAL_PAYMENT";
        LocalDate dueDate = TEST_MONTH.plusDays(14);
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, source_contract_id,
                    recipient_agent_id, commission_item_id, policy_version_id, installment_no,
                    settlement_month, due_date, amount, cashflow_type, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PAYMENT', 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class,
                paymentStage, sourceType,
                "IT-048-03:" + suffix, contractId, agentId, commissionItemId, policyVersionId,
                installmentNo, TEST_MONTH, dueDate, new BigDecimal(amount));
        Long attributionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                    attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, attribution_method
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, ?, 'INCLUDED', 'DIRECT')
                RETURNING transaction_attribution_id
                """, Long.class,
                transactionId, contractId, dueDate, TEST_MONTH, new BigDecimal(amount));
        if ("CONFIRMED".equals(status)) {
            jdbcTemplate.update("""
                    UPDATE fgc.commission_transaction
                       SET status = 'CONFIRMED'
                     WHERE commission_transaction_id = ?
                    """, transactionId);
        }
        return new PaymentInsert(transactionId, attributionId);
    }

    private Long insertPostedJournal(
            String journalType,
            String sourceEntityType,
            Long sourceEntityId,
            Long contractId,
            Long agentId,
            Long commissionItemId,
            String amount,
            String debitAccountCode,
            String creditAccountCode
    ) {
        Long journalHeaderId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.journal_header (
                    journal_no, journal_date, journal_type, source_entity_type,
                    source_entity_id, contract_id, validation_run_id, policy_version_id, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, NULL, 'DRAFT')
                RETURNING journal_header_id
                """, Long.class,
                "IT-048-03-" + journalType + "-" + sourceEntityId,
                TEST_MONTH.plusDays(14), journalType, sourceEntityType,
                String.valueOf(sourceEntityId), contractId, validationRunId);
        Long debitAccountId = id(
                "SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?", debitAccountCode);
        Long creditAccountId = id(
                "SELECT journal_account_id FROM fgc.journal_account WHERE account_code = ?", creditAccountCode);
        BigDecimal journalAmount = new BigDecimal(amount);
        jdbcTemplate.update("""
                INSERT INTO fgc.journal_line (
                    journal_header_id, line_no, journal_account_id, debit_amount, credit_amount,
                    contract_id, agent_id, payment_stage, commission_item_id
                ) VALUES (?, 1, ?, ?, 0, ?, ?, 'GA_TO_FC', ?),
                         (?, 2, ?, 0, ?, ?, ?, 'GA_TO_FC', ?)
                """,
                journalHeaderId, debitAccountId, journalAmount, contractId, agentId, commissionItemId,
                journalHeaderId, creditAccountId, journalAmount, contractId, agentId, commissionItemId);
        jdbcTemplate.update(
                "UPDATE fgc.journal_header SET status = 'POSTED' WHERE journal_header_id = ?",
                journalHeaderId);
        return journalHeaderId;
    }

    private Long id(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Long.class, args);
    }

    private record PaymentInsert(Long transactionId, Long attributionId) {
    }
}
