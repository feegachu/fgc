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
    @Autowired com.susukkang.fgc.arbitrage.repository.ArbitrageCheckRepository arbitrage;
    private Long contractId;
    private Long validationRunId;
    private PaymentStage paymentStage = PaymentStage.GA_TO_FC;
    private boolean legacyPaymentWithoutSourceContract;
    @Autowired com.susukkang.fgc.exceptioncase.service.ExceptionPaymentCorrectionService exceptionPayments;
    @Autowired com.susukkang.fgc.exceptioncase.service.ExceptionCaseService exceptionActions;
    @Autowired com.susukkang.fgc.cap.service.CapCheckService capChecks;

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(PaymentStage.class)
    @WithMockUser(roles = "SETTLEMENT")
    void correctionPreviewReplacesNineHundredThousandWithSixHundredThousandWithoutImportingTheScheduleTotal(PaymentStage stage) {
        Fixture f = fixture(stage, "900000", "988000");
        jdbcTemplate.update("UPDATE fgc.insurance_contract SET contract_date=?, monthly_equivalent_first_premium=76000 WHERE contract_id=?", TEST_MONTH, contractId);
        startException(f.exception());
        var old = payments.get(f.payment());
        var result = exceptionPayments.preview(f.exception(), new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(
                old.paymentId(), old.updatedAt(), paymentBody(old, "600000", TEST_MONTH)), 1L);
        assertThat(result.capPreview()).hasSize(1);
        var cap = result.capPreview().getFirst();
        assertThat(cap.existingIncludedAmount()).isZero();
        assertThat(cap.candidateAmount()).isEqualTo(600000);
        assertThat(cap.includedAmount()).isEqualTo(600000);
        assertThat(payments.get(f.payment()).amount()).isEqualByComparingTo("900000");
        assertThat(status(f.exception())).isEqualTo("IN_REVIEW");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(PaymentStage.class)
    @WithMockUser(roles = "SETTLEMENT")
    void scheduleBasedCapRoutesToItsSourceAndOnlyResolvesAfterTheSchedulePasses(PaymentStage stage) {
        Fixture f = fixture(stage, "900000", "988000");
        jdbcTemplate.update("UPDATE fgc.insurance_contract SET contract_date=?, monthly_equivalent_first_premium=76000 WHERE contract_id=?", LocalDate.of(2026, 7, 1), contractId);
        var cap = capChecks.calculateAndSave(com.susukkang.fgc.cap.dto.CapCalculationCommand.manual(
                contractId, stage, LocalDate.of(2026, 7, 1), null));
        assertThat(cap.result().resultStatus()).as(cap.result().toString()).isEqualTo(CapResultStatus.VIOLATION);
        Long id = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.exception_case(exception_key,exception_type,severity,status,contract_id,
                    source_entity_type,source_entity_id,cap_check_id,title,description)
                VALUES (?,'CAP_VIOLATION','CRITICAL','NEW',?,'CAP_CHECK',?,?,'예상 스케줄 한도 초과','예상액 988000')
                RETURNING exception_case_id
                """, Long.class, "EXCP-PLAN-"+UUID.randomUUID(), contractId, cap.capCheckId().toString(), cap.capCheckId());
        startException(id);
        var context = exceptionPayments.context(id, 1L);
        assertThat(context.scheduledBasis()).isTrue();
        assertThat(context.correctionTarget()).isEqualTo("SCHEDULE");
        assertThat(context.paymentStage()).isEqualTo(stage);
        assertThat(context.payments()).isEmpty();
        assertThat(context.scheduleLink()).startsWith("/schedules/");
        var old = payments.get(f.payment());
        var request = new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(
                old.paymentId(), old.updatedAt(), paymentBody(old,"600000",TEST_MONTH));
        assertThatThrownBy(() -> exceptionPayments.preview(id, request, 1L)).isInstanceOf(FgcBusinessException.class);
        assertThatThrownBy(() -> exceptionPayments.correct(id, request, 1L,"admin")).isInstanceOf(FgcBusinessException.class);
        assertThat(payments.get(f.payment()).amount()).isEqualByComparingTo("900000");
        var blocked = exceptionPayments.recheckSchedule(id, 1L, "admin");
        assertThat(blocked.resolved()).isFalse();
        assertThat(status(id)).isEqualTo("IN_REVIEW");
        jdbcTemplate.update("UPDATE fgc.schedule_line SET expected_amount=600000 WHERE schedule_header_id IN (SELECT schedule_header_id FROM fgc.schedule_header WHERE contract_id=? AND payment_stage=?)", contractId, stage.name());
        var resolved = exceptionPayments.recheckSchedule(id, 1L, "admin");
        assertThat(resolved.resolved()).isTrue();
        assertThat(resolved.action().actionType()).isEqualTo("CORRECT");
        assertThat(status(id)).isEqualTo("RESOLVED");
        assertThat(payments.get(f.payment()).amount()).isEqualByComparingTo("900000");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"EXPECTED_MISSING", "INVALID_CONTRACT_PAYMENT"})
    @WithMockUser(roles = "SETTLEMENT")
    void zeroExpectedAmountRoutesToScheduleOrContractInsteadOfEditingPayments(String resultType) {
        Fixture f = fixture(PaymentStage.INSURER_TO_GA, "700000", "100000");
        jdbcTemplate.update("UPDATE fgc.reconciliation_result SET expected_total_amount=0,result_type=? WHERE reconciliation_run_id=? AND contract_id=?", resultType, f.run(), contractId);
        startException(f.exception());
        var context = exceptionPayments.context(f.exception(), 1L);
        assertThat(context.correctionTarget()).isEqualTo(resultType.equals("EXPECTED_MISSING") ? "SCHEDULE" : "CONTRACT");
        assertThat(context.payments()).isEmpty();
        assertThat(context.scheduleLink()).isEqualTo("/contracts/" + contractId + "?tab=schedules");
        var old = payments.get(f.payment());
        assertThatThrownBy(() -> exceptionPayments.correct(f.exception(), new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(
                old.paymentId(), old.updatedAt(), paymentBody(old, "100000", TEST_MONTH)), 1L, "admin")).isInstanceOf(FgcBusinessException.class);
        assertThat(payments.get(f.payment()).amount()).isEqualByComparingTo("700000");
    }

    @Test
    @WithMockUser(roles = "SETTLEMENT")
    void paymentWithContractOnlyOnAttributionCanBeCorrectedAfterSuccessfulPrecheck() {
        legacyPaymentWithoutSourceContract = true;
        Fixture f = fixture();
        startException(f.exception());
        var old = payments.get(f.payment());
        var request = new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(
                old.paymentId(), old.updatedAt(), paymentBody(old, "100000", TEST_MONTH));
        assertThat(exceptionPayments.preview(f.exception(), request, 1L).confirmable()).isTrue();
        assertThat(exceptionPayments.correct(f.exception(), request, 1L, "admin").resolved()).isTrue();
    }

    @Test
    @WithMockUser(roles = "SETTLEMENT")
    void exceptionEditorCorrectsTheActualPaymentAndOnlyResolvesAfterReconciliationMatches() {
        Fixture f = fixture();
        startException(f.exception());
        var old = payments.get(f.payment());
        assertThat(exceptionPayments.context(f.exception(), 1L).payments()).extracting(p -> p.paymentId()).contains(f.payment());
        assertThat(exceptionPayments.context(f.exception(), 1L).paymentJournalIds()).containsEntry(f.payment(), f.journal());
        var result = exceptionPayments.correct(f.exception(), new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(
                old.paymentId(), old.updatedAt(), paymentBody(old, "100000", old.settlementMonth())), 1L, "admin");
        assertThat(result.resolved()).as(result.message()).isTrue();
        assertThat(result.action().actionType()).isEqualTo("CORRECT");
        assertThat(payments.get(f.payment()).status()).isEqualTo(CommissionPaymentStatus.CANCELLED);
        assertThat(result.payment().status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(result.payment().amount()).isEqualByComparingTo("100000");
    }

    @Test
    @WithMockUser(roles = "SETTLEMENT")
    void incompletePaymentCorrectionKeepsTheExceptionInReviewAndOffersTheReplacementForFurtherEditing() {
        Fixture f = fixture();
        startException(f.exception());
        var old = payments.get(f.payment());
        var result = exceptionPayments.correct(f.exception(), new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(
                old.paymentId(), old.updatedAt(), paymentBody(old, "100001", old.settlementMonth())), 1L, "admin");
        assertThat(result.resolved()).isFalse();
        assertThat(status(f.exception())).isEqualTo("IN_REVIEW");
        assertThat(result.remainingIssues()).extracting(i -> i.resultType()).contains("AMOUNT_DIFFERENCE");
        assertThat(result.message()).contains("예상 100,000원 / 실제 100,001원");
        assertThat(exceptionPayments.context(f.exception(), 1L).payments()).extracting(p -> p.paymentId()).contains(result.payment().paymentId());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans = {false, true})
    @WithMockUser(roles = "SETTLEMENT")
    void capDraftCorrectionInfersReductionOrDeferralAndRevalidatesBeforeResolving(boolean defer) {
        Fixture f = fixture();
        jdbcTemplate.update("UPDATE fgc.insurance_contract SET contract_date=? WHERE contract_id=?", TEST_MONTH, contractId);
        var seed = payments.get(f.payment());
        var draft = payments.create(paymentBody(seed, "1000000000", TEST_MONTH));
        assertThatThrownBy(() -> payments.confirm(draft.paymentId(), "EXCP-CAP-TEST-" + draft.paymentId()))
                .isInstanceOf(com.susukkang.fgc.transaction.service.CommissionPaymentConfirmationRejectedException.class);
        Long exceptionId = id("SELECT exception_case_id FROM fgc.exception_case WHERE source_entity_type='COMMISSION_TRANSACTION' AND source_entity_id=? AND exception_type='CAP_VIOLATION' ORDER BY exception_case_id LIMIT 1", draft.paymentId().toString());
        startException(exceptionId);
        var result = exceptionPayments.correct(exceptionId, new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(
                draft.paymentId(), payments.get(draft.paymentId()).updatedAt(),
                paymentBody(draft, defer ? "1000000000" : "1", defer ? TEST_MONTH.plusMonths(12) : TEST_MONTH)), 1L, "admin");
        assertThat(result.resolved()).isTrue();
        assertThat(result.action().actionType()).isEqualTo(defer ? "DEFER" : "REDUCE");
        assertThat(result.payment().status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
    }

    @Test
    @WithMockUser(roles = "SETTLEMENT")
    void sourceCorrectionRejectsAnotherReviewerAndSelfAssignment() {
        Fixture f = fixture();
        assertThatThrownBy(() -> exceptionActions.userAction(f.exception(),
                new com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest(ExceptionActionType.ASSIGN, null, null, 1L), 1L, "admin"))
                .isInstanceOf(FgcBusinessException.class);
        startException(f.exception());
        assertThatThrownBy(() -> exceptionPayments.context(f.exception(), 2L)).isInstanceOf(FgcBusinessException.class);
        assertThat(payments.get(f.payment()).status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(booleans={false,true})
    @WithMockUser(roles="SETTLEMENT")
    void partialDeferralConfirmsOnlyCurrentAmountAndKeepsTheExcessAsAFutureDraft(boolean settlementSupport) {
        Fixture f=fixture();
        jdbcTemplate.update("UPDATE fgc.insurance_contract SET contract_date=? WHERE contract_id=?",TEST_MONTH,contractId);
        var draftBody=paymentBody(payments.get(f.payment()),"1000000000",TEST_MONTH);
        if (settlementSupport) {
            Long item=id("SELECT commission_item_id FROM fgc.commission_item WHERE item_code='SETTLEMENT_SUPPORT'");
            var a=draftBody.attributions().getFirst();
            draftBody=new com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest(draftBody.sourceType(),draftBody.sourceBusinessKey(),
                    draftBody.contractId(),draftBody.agentId(),item,draftBody.amount(),draftBody.settlementMonth(),draftBody.cashflowType(),
                    draftBody.scheduledPaymentDate(),draftBody.paymentStage(),draftBody.allocationPolicyVersion(),List.of(
                    new com.susukkang.fgc.transaction.dto.CommissionPaymentAttributionRequest(a.contractId(),a.attributionDate(),a.amount(),
                    a.inclusionDecisionStatus(),a.exclusionType(),a.inclusionDecisionReason(),"월별 신계약 배부",a.evidenceRef(),AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY)),draftBody.evidenceRef(),draftBody.note());
        }
        var draft=payments.create(draftBody);
        assertThatThrownBy(() -> payments.confirm(draft.paymentId(),"PARTIAL-"+draft.paymentId())).isInstanceOf(com.susukkang.fgc.transaction.service.CommissionPaymentConfirmationRejectedException.class);
        Long exception=id("SELECT exception_case_id FROM fgc.exception_case WHERE source_entity_type='COMMISSION_TRANSACTION' AND source_entity_id=? AND exception_type='CAP_VIOLATION'",draft.paymentId().toString());
        startException(exception);
        var result=exceptionPayments.correct(exception,new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(
                draft.paymentId(),payments.get(draft.paymentId()).updatedAt(),paymentBody(draft,"1",TEST_MONTH),null,null,"DEFER",new BigDecimal("999999999"),TEST_MONTH.plusMonths(12)),1L,"admin");
        assertThat(result.resolved()).isTrue();
        assertThat(result.action().actionType()).isEqualTo("DEFER");
        assertThat(result.payment().amount()).isEqualByComparingTo("1");
        assertThat(result.deferredPayment().amount()).isEqualByComparingTo("999999999");
        assertThat(result.deferredPayment().status()).isEqualTo(CommissionPaymentStatus.DRAFT);
        assertThat(result.deferredPayment().scheduledPaymentDate()).isEqualTo(TEST_MONTH.plusMonths(12));
        if (settlementSupport) {
            assertThat(result.deferredPayment().settlementMonth()).isEqualTo(TEST_MONTH);
            assertThat(result.deferredPayment().attributions().getFirst().attributionMethod()).isEqualTo(AttributionMethod.SETTLEMENT_SUPPORT_MONTHLY);
        }
    }

    @Test @WithMockUser(roles="SETTLEMENT")
    void duplicateRecordCanBeReversedWithoutCreatingADeductionAndResolvesAsCancel() {
        Fixture f=fixture(PaymentStage.GA_TO_FC,"150000","100000");
        var old=payments.get(f.payment());
        Long policy=id("SELECT policy_version_id FROM fgc.commission_transaction WHERE commission_transaction_id=?",f.payment());
        var other=insertPayment(contractId,policy,f.item(),f.agent(),1,"100000","CONFIRMED",UUID.randomUUID().toString());
        insertPostedJournal("CONFIRMED_FC_PAYOUT","COMMISSION_TRANSACTION",other.transactionId(),contractId,f.agent(),f.item(),"100000","CONFIRMED_PAYOUT_EXPENSE","CONFIRMED_PAYOUT_PAYABLE");
        jdbcTemplate.update("UPDATE fgc.reconciliation_run SET superseded_yn=true WHERE reconciliation_run_id=?",f.run());
        Long run=jdbcTemplate.queryForObject("INSERT INTO fgc.reconciliation_run(settlement_month,payment_stage,insurer_id,validation_run_id,corrects_reconciliation_run_id) SELECT settlement_month,payment_stage,insurer_id,validation_run_id,reconciliation_run_id FROM fgc.reconciliation_run WHERE reconciliation_run_id=? RETURNING reconciliation_run_id",Long.class,f.run());
        jdbcTemplate.update("UPDATE fgc.reconciliation_run SET status='RUNNING',started_at=clock_timestamp() WHERE reconciliation_run_id=?",run);
        Long insurer=id("SELECT insurer_id FROM fgc.insurance_contract WHERE contract_id=?",contractId);
        execution.executeInTransaction(new ReconciliationExecutionRequest(run,validationRunId,TEST_MONTH,PaymentStage.GA_TO_FC,insurer,1L)); detection.bulkCreate(run);
        Long exception=id("SELECT e.exception_case_id FROM fgc.exception_case e JOIN fgc.reconciliation_result r ON e.source_entity_id=r.reconciliation_result_id::text WHERE e.source_entity_type='RECONCILIATION_RESULT' AND r.reconciliation_run_id=? AND r.contract_id=? AND r.result_type='DUPLICATE'",run,contractId);
        startException(exception);
        var candidates = exceptionPayments.context(exception, 1L).paymentJournalIds();
        assertThat(candidates).containsEntry(old.paymentId(), f.journal()).containsKey(other.transactionId());
        var result=exceptionPayments.cancelDuplicate(exception,new com.susukkang.fgc.exceptioncase.dto.ExceptionDuplicateCancelRequest(old.paymentId(),old.updatedAt(),"중복 기록 확인"),1L,"admin");
        assertThat(result.resolved()).isTrue();
        assertThat(result.action().actionType()).isEqualTo("CANCEL");
        assertThat(payments.get(old.paymentId()).status()).isEqualTo(CommissionPaymentStatus.CANCELLED);
        assertThat(payments.get(other.transactionId()).status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fgc.journal_header WHERE journal_header_id=?", String.class, f.journal())).isEqualTo("REVERSED");
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM fgc.journal_header WHERE journal_header_id=?", String.class, candidates.get(other.transactionId()))).isEqualTo("POSTED");
    }

    private void startException(Long id) {
        exceptionActions.userAction(id, new com.susukkang.fgc.exceptioncase.dto.ExceptionActionRequest(
                ExceptionActionType.START_REVIEW, null, null), 1L, "admin");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(PaymentStage.class)
    @WithMockUser(roles = "SETTLEMENT")
    void missingActualCanBeRegisteredFromTheExceptionAndOnlyClosesAfterMatching(PaymentStage stage) {
        Fixture f = fixture(stage, "50000", "100000");
        corrections.reverse(new ReverseJournalCommand(f.journal(), "누락 지급 정정 준비", null, 1L));
        Long currentRun = id("SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE corrects_reconciliation_run_id=?", f.run());
        Long exceptionId = id("""
                SELECT e.exception_case_id FROM fgc.exception_case e JOIN fgc.reconciliation_result r
                  ON e.source_entity_type='RECONCILIATION_RESULT' AND e.source_entity_id=r.reconciliation_result_id::text
                WHERE r.reconciliation_run_id=? AND r.contract_id=? AND r.result_type='ACTUAL_MISSING'
                """, currentRun, contractId);
        startException(exceptionId);
        var context = exceptionPayments.context(exceptionId, 1L);
        assertThat(context.suggestedPayment()).isNotNull();
        assertThat(context.payments()).isEmpty();
        assertThat(context.canRegisterPayment()).isTrue();
        var result = exceptionPayments.correct(exceptionId,
                new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(null, null, context.suggestedPayment()), 1L, "admin");
        assertThat(result.payment().status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(result.resolved()).as(result.message()).isTrue();
        assertThat(result.action().actionType()).isEqualTo("RECONCILE_AGAIN");
    }

    @Test
    @WithMockUser(roles = "SETTLEMENT")
    void paymentCorrectionOnlyShowsTheSelectedRecipientsIssueAcrossReconciliations() {
        Fixture f = fixture(PaymentStage.GA_TO_FC, "50000", "22000");
        var otherAgents = jdbcTemplate.queryForList("SELECT agent_id FROM fgc.agent WHERE agent_id<>? ORDER BY agent_id LIMIT 2", Long.class, f.agent());
        assertThat(otherAgents).hasSize(2);
        for (int i = 0; i < otherAgents.size(); i++) {
            // 금액이 같아도 다른 수령인의 예외는 표시하지 않는다.
            String amount = i == 0 ? "44000" : "22000";
            Long agent = otherAgents.get(i);
            Long line = id("""
                    INSERT INTO fgc.schedule_line(schedule_header_id,line_no,contract_month_no,installment_no,due_date,
                        commission_item_id,beneficiary_agent_id,basis_code,basis_amount,calculation_type,fixed_amount,expected_amount)
                    SELECT l.schedule_header_id,?,l.contract_month_no,l.installment_no,l.due_date,
                        l.commission_item_id,?,l.basis_code,?,l.calculation_type,?,?
                    FROM fgc.schedule_line l JOIN fgc.schedule_header h USING(schedule_header_id)
                    WHERE h.contract_id=? AND h.payment_stage='GA_TO_FC' AND l.line_no=1
                    RETURNING schedule_line_id
                    """, i + 2, agent, new BigDecimal(amount), new BigDecimal(amount), new BigDecimal(amount), contractId);
            insertPostedJournal("EXPECTED_FC_PAYOUT", "SCHEDULE_LINE", line, contractId, agent, f.item(),
                    amount, "EXPECTED_PAYOUT_EXPENSE", "EXPECTED_PAYOUT_PAYABLE");
        }
        corrections.reverse(new ReverseJournalCommand(f.journal(), "누락 지급 정정 준비", null, 1L));
        Long run = id("SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE corrects_reconciliation_run_id=?", f.run());
        assertThat(id("SELECT COUNT(*) FROM fgc.reconciliation_result WHERE reconciliation_run_id=? AND contract_id=? AND result_type='ACTUAL_MISSING'", run, contractId)).isEqualTo(3L);
        Long exception = id("""
                SELECT e.exception_case_id FROM fgc.exception_case e JOIN fgc.reconciliation_result r
                  ON e.source_entity_type='RECONCILIATION_RESULT' AND e.source_entity_id=r.reconciliation_result_id::text
                WHERE r.reconciliation_run_id=? AND r.contract_id=? AND r.expected_agent_id=? AND r.result_type='ACTUAL_MISSING'
                """, run, contractId, f.agent());
        startException(exception);
        var context = exceptionPayments.context(exception, 1L);
        assertThat(context.remainingIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.expectedAmount()).isEqualByComparingTo("22000");
            assertThat(issue.actualAmount()).isZero();
        });
        var draft = payments.create(context.suggestedPayment());
        jdbcTemplate.update("UPDATE fgc.transaction_attribution SET attributed_amount=21000 WHERE commission_transaction_id=?", draft.paymentId());
        jdbcTemplate.update("UPDATE fgc.commission_transaction SET amount=21000,status='CONFIRMED' WHERE commission_transaction_id=?", draft.paymentId());
        insertPostedJournal("CONFIRMED_FC_PAYOUT", "COMMISSION_TRANSACTION", draft.paymentId(), contractId, f.agent(), f.item(),
                "21000", "CONFIRMED_PAYOUT_EXPENSE", "CONFIRMED_PAYOUT_PAYABLE");
        assertThat(exceptionPayments.recheckSchedule(exception, 1L, "admin").resolved()).isFalse();
        var current = exceptionPayments.context(exception, 1L);
        assertThat(current.remainingIssues()).singleElement().satisfies(issue -> {
            assertThat(issue.resultType()).isEqualTo("AMOUNT_DIFFERENCE");
            assertThat(issue.expectedAmount()).isEqualByComparingTo("22000");
            assertThat(issue.actualAmount()).isEqualByComparingTo("21000");
            assertThat(issue.resultId()).isNotEqualTo(context.remainingIssues().getFirst().resultId());
        });
    }

    @Test
    @WithMockUser(roles = "SETTLEMENT")
    void invalidContractPaymentIncludesItsOriginalPaymentForTheRecoveryChecklist() {
        Fixture f = fixture();
        jdbcTemplate.update("UPDATE fgc.reconciliation_result SET result_type='INVALID_CONTRACT_PAYMENT',expected_total_amount=0 WHERE reconciliation_run_id=? AND contract_id=?", f.run(), contractId);
        startException(f.exception());
        var context = exceptionPayments.context(f.exception(), 1L);
        assertThat(context.correctionTarget()).isEqualTo("RECOVERY");
        assertThat(context.payments()).extracting(p -> p.paymentId()).containsExactly(f.payment());
        assertThat(context.paymentJournalIds()).containsEntry(f.payment(), f.journal());
    }

    @Test
    @WithMockUser(roles = "SETTLEMENT")
    void amountDifferenceCannotRegisterAnotherPaymentAndRequiresSettlementEvidenceForActualTransfers() {
        Fixture f = fixture(PaymentStage.GA_TO_FC, "825000", "845000");
        startException(f.exception());
        var old = payments.get(f.payment());
        assertThat(exceptionPayments.context(f.exception(), 1L).canRegisterPayment()).isFalse();
        assertThatThrownBy(() -> exceptionPayments.preview(f.exception(), new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(
                null, null, paymentBody(old, "20000", TEST_MONTH)), 1L)).isInstanceOf(FgcBusinessException.class);
        var body = paymentBody(old, "845000", TEST_MONTH);
        assertThatThrownBy(() -> exceptionPayments.preview(f.exception(), new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(
                old.paymentId(), old.updatedAt(), body, "SETTLEMENT_COMPLETED", ""), 1L)).isInstanceOf(FgcBusinessException.class);
        var result = exceptionPayments.correct(f.exception(), new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(
                old.paymentId(), old.updatedAt(), body, "SETTLEMENT_COMPLETED", "추가 송금 20,000원 · BANK-1"), 1L, "admin");
        assertThat(result.resolved()).isTrue();
        assertThat(result.action().actionType()).isEqualTo("CORRECT");
        assertThat(result.action().reason()).contains("BANK-1");
        assertThat(payments.get(f.payment()).status()).isEqualTo(CommissionPaymentStatus.CANCELLED);
        assertThat(result.payment().amount()).isEqualByComparingTo("845000");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(PaymentStage.class)
    @WithMockUser(roles = "SETTLEMENT")
    void missingActualOffersTheExistingDraftAndConfirmsItWithoutCreatingAnotherPayment(PaymentStage stage) {
        Fixture f = fixture(stage);
        corrections.reverse(new ReverseJournalCommand(f.journal(), "누락 초안 확인", null, 1L));
        Long currentRun = id("SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE corrects_reconciliation_run_id=?", f.run());
        Long exceptionId = id("""
                SELECT e.exception_case_id FROM fgc.exception_case e JOIN fgc.reconciliation_result r
                  ON e.source_entity_type='RECONCILIATION_RESULT' AND e.source_entity_id=r.reconciliation_result_id::text
                WHERE r.reconciliation_run_id=? AND r.contract_id=? AND r.result_type='ACTUAL_MISSING'
                """, currentRun, contractId);
        startException(exceptionId);
        var suggestion = exceptionPayments.context(exceptionId, 1L).suggestedPayment();
        var draft = payments.create(suggestion);
        var context = exceptionPayments.context(exceptionId, 1L);
        assertThat(context.canRegisterPayment()).isFalse();
        assertThat(context.payments()).extracting(p -> p.paymentId()).contains(draft.paymentId());
        assertThatThrownBy(() -> exceptionPayments.correct(exceptionId,
                new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(null, null, suggestion), 1L, "admin"))
                .isInstanceOf(FgcBusinessException.class);
        var result = exceptionPayments.correct(exceptionId,
                new com.susukkang.fgc.exceptioncase.dto.ExceptionPaymentCorrectionRequest(draft.paymentId(), draft.updatedAt(), suggestion), 1L, "admin");
        assertThat(result.payment().paymentId()).isEqualTo(draft.paymentId());
        assertThat(result.resolved()).isTrue();
        assertThat(result.action().actionType()).isEqualTo("RECONCILE_AGAIN");
    }

    private com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest paymentBody(
            com.susukkang.fgc.transaction.dto.CommissionPaymentResponse old, String amount, LocalDate month) {
        var attribution = old.attributions().getFirst();
        return new com.susukkang.fgc.transaction.dto.CommissionPaymentCreateRequest("GA_MANUAL_PAYMENT", "EXCP-TEST-" + UUID.randomUUID(),
                old.contractId() == null ? attribution.contractId() : old.contractId(), old.agentId(), old.commissionItemId(), new BigDecimal(amount), month, old.cashflowType(), month,
                old.paymentStage(), old.allocationPolicyVersion(), List.of(new com.susukkang.fgc.transaction.dto.CommissionPaymentAttributionRequest(
                attribution.contractId(), month, new BigDecimal(amount), attribution.inclusionDecisionStatus(), attribution.exclusionType(),
                "예외 원인 수정", attribution.allocationBasis(), attribution.evidenceRef(), attribution.attributionMethod())), old.evidenceRef(), null);
    }

    @Test
    void repostCancelsOldPaymentAndMatchesReplacementWithoutDuplicateAndResolvesException() {
        Fixture f = fixture();
        assertThat(arbitrage.sumPlannedCommissionAmount(contractId, PaymentStage.GA_TO_FC,
                TEST_MONTH.plusMonths(1))).isEqualByComparingTo("50000");
        var numbers = corrections.previewRepostNumbers(TEST_MONTH);
        var result = corrections.reverseAndRepost(command(f, "100000"));
        assertThat(jdbcTemplate.queryForObject("SELECT journal_no FROM fgc.journal_header WHERE journal_header_id=?", String.class, result.journalHeaderId())).isEqualTo(numbers.reversalJournalNo());
        assertThat(jdbcTemplate.queryForObject("SELECT journal_no FROM fgc.journal_header WHERE journal_header_id=?", String.class, result.repostedJournalHeaderId())).isEqualTo(numbers.repostedJournalNo());
        assertThat(arbitrage.sumPlannedCommissionAmount(contractId, PaymentStage.GA_TO_FC,
                TEST_MONTH.plusMonths(1))).isEqualByComparingTo("0");
        assertThat(payments.get(f.payment()).status()).isEqualTo(CommissionPaymentStatus.CANCELLED);
        Long replacement = id("SELECT replacement_payment_id FROM fgc.payment_journal_correction WHERE original_payment_id=?", f.payment());
        assertThat(payments.get(replacement).status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(payments.get(replacement).amount()).isEqualByComparingTo("100000");
        assertThat(id("SELECT schedule_line_id FROM fgc.transaction_attribution WHERE commission_transaction_id=?", replacement)).isNotNull();
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
        assertThat(arbitrage.sumPlannedCommissionAmount(contractId, PaymentStage.GA_TO_FC,
                TEST_MONTH.plusMonths(1))).isEqualByComparingTo("100000");
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

    @Test
    void secondCorrectionResolvesExceptionStillReferencingFirstReconciliation() {
        Fixture f = fixture();
        var first = corrections.reverseAndRepost(command(f,"70000"));
        Long replacement = id("SELECT replacement_payment_id FROM fgc.payment_journal_correction WHERE original_payment_id=?",f.payment());
        Long secondRun = id("SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE corrects_reconciliation_run_id=?",f.run());
        assertThat(resultType(secondRun)).isEqualTo("AMOUNT_DIFFERENCE");
        assertThat(status(f.exception())).isEqualTo("NEW");
        // 같은 validation_run의 재감지는 발생 이력을 중복 저장하지 않으므로 최초 원천 참조가 유지된다.
        assertThat(id("SELECT r.reconciliation_run_id FROM fgc.exception_case e JOIN fgc.reconciliation_result r ON e.source_entity_id=CAST(r.reconciliation_result_id AS varchar) WHERE e.exception_case_id=?",f.exception())).isEqualTo(f.run());
        Fixture next = new Fixture(replacement,first.repostedJournalHeaderId(),secondRun,f.exception(),f.agent(),f.item());
        corrections.reverseAndRepost(command(next,"100000"));
        Long thirdRun = id("SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE corrects_reconciliation_run_id=?",secondRun);
        assertThat(resultType(thirdRun)).isEqualTo("MATCHED");
        assertThat(status(f.exception())).isEqualTo("RESOLVED");
        assertThat(id("SELECT COUNT(*) FROM fgc.exception_action WHERE exception_case_id=?",f.exception())).isEqualTo(2);
    }

    @Test
    void insurerStatementCorrectionRetainsProvenanceAndMatchesManualReplacement() {
        Fixture f = fixture(PaymentStage.INSURER_TO_GA);
        Long originalBatch = id("SELECT statement_batch_id FROM fgc.commission_transaction WHERE commission_transaction_id=?",f.payment());
        assertThat(originalBatch).isNotNull();
        corrections.reverseAndRepost(command(f,"100000"));
        Long replacement = id("SELECT replacement_payment_id FROM fgc.payment_journal_correction WHERE original_payment_id=?",f.payment());
        assertThat(payments.get(replacement).status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
        assertThat(id("SELECT statement_batch_id FROM fgc.commission_transaction WHERE commission_transaction_id=?",replacement)).isNull();
        assertThat(id("SELECT o.statement_batch_id FROM fgc.payment_journal_correction pc JOIN fgc.commission_transaction o ON o.commission_transaction_id=pc.original_payment_id WHERE pc.replacement_payment_id=?",replacement)).isEqualTo(originalBatch);
        Long current = id("SELECT reconciliation_run_id FROM fgc.reconciliation_run WHERE corrects_reconciliation_run_id=?",f.run());
        assertThat(resultType(current)).isEqualTo("MATCHED");
        assertThat(status(f.exception())).isEqualTo("RESOLVED");
    }

    @Test
    void finalizedDifferentValidationIncludingPaymentPreventsCorrection() {
        Fixture f = fixture();
        Long originalValidation = validationRunId;
        Long other = jdbcTemplate.queryForObject("INSERT INTO fgc.validation_run(validation_month,run_no,status) SELECT ?,MAX(run_no)+1,'CREATED' FROM fgc.validation_run WHERE validation_month=? RETURNING validation_run_id",Long.class,TEST_MONTH,TEST_MONTH);
        Long insurer = id("SELECT insurer_id FROM fgc.insurance_contract WHERE contract_id=?",contractId);
        Long otherReco = jdbcTemplate.queryForObject("INSERT INTO fgc.reconciliation_run(settlement_month,payment_stage,insurer_id,validation_run_id) VALUES (?,'GA_TO_FC',?,?) RETURNING reconciliation_run_id",Long.class,TEST_MONTH,insurer,other);
        jdbcTemplate.update("UPDATE fgc.reconciliation_run SET status='RUNNING',started_at=clock_timestamp() WHERE reconciliation_run_id=?",otherReco);
        execution.executeInTransaction(new ReconciliationExecutionRequest(otherReco,other,TEST_MONTH,PaymentStage.GA_TO_FC,insurer,1L));
        jdbcTemplate.update("UPDATE fgc.validation_run SET status='RUNNING' WHERE validation_run_id=?",other);
        jdbcTemplate.update("UPDATE fgc.validation_run SET status='COMPLETED',current_step=8 WHERE validation_run_id=?",other);
        jdbcTemplate.update("UPDATE fgc.validation_run SET status='FINALIZED',current_step=10,finalized_at=clock_timestamp() WHERE validation_run_id=?",other);
        assertThat(validationRunId).isEqualTo(originalValidation);
        assertThatThrownBy(() -> corrections.reverseAndRepost(command(f,"100000")))
                .isInstanceOfSatisfying(FgcBusinessException.class,error -> assertThat(error.getErrorCode()).isEqualTo(com.susukkang.fgc.common.exception.FgcErrorCode.VRUN_003));
        assertThat(payments.get(f.payment()).status()).isEqualTo(CommissionPaymentStatus.CONFIRMED);
    }

    private Fixture fixture() { return fixture(PaymentStage.GA_TO_FC); }

    private Fixture fixture(PaymentStage stage) {
        return fixture(stage, "50000", "100000");
    }

    private Fixture fixture(PaymentStage stage, String actualAmount, String expectedAmount) {
        paymentStage = stage;
        boolean payout = stage == PaymentStage.GA_TO_FC;
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
        Long line = insertSchedule(contractId,policy,item,agent,1,expectedAmount);
        insertPostedJournal(payout ? "EXPECTED_FC_PAYOUT" : "EXPECTED_INSURER_INCOME","SCHEDULE_LINE",line,contractId,payout ? agent : null,item,expectedAmount,payout ? "EXPECTED_PAYOUT_EXPENSE" : "EXPECTED_RECEIVABLE",payout ? "EXPECTED_PAYOUT_PAYABLE" : "EXPECTED_INCOME");
        var old = insertPayment(contractId,policy,item,agent,1,actualAmount,"DRAFT",UUID.randomUUID().toString());
        jdbcTemplate.update("UPDATE fgc.transaction_attribution SET allocation_basis_snapshot='{\"inclusionDecisionReason\":\"direct\",\"allocationBasis\":\"DIRECT\"}'::jsonb,evidence_ref='548' WHERE transaction_attribution_id=?", old.attributionId());
        jdbcTemplate.update("UPDATE fgc.commission_transaction SET status='CONFIRMED' WHERE commission_transaction_id=?",old.transactionId());
        Long journal = insertPostedJournal(payout ? "CONFIRMED_FC_PAYOUT" : "ACTUAL_INSURER_STATEMENT","COMMISSION_TRANSACTION",old.transactionId(),contractId,payout ? agent : null,item,actualAmount,payout ? "CONFIRMED_PAYOUT_EXPENSE" : "ACTUAL_RECEIVABLE",payout ? "CONFIRMED_PAYOUT_PAYABLE" : "ACTUAL_INCOME");
        Long run = jdbcTemplate.queryForObject("INSERT INTO fgc.reconciliation_run(settlement_month,payment_stage,insurer_id,validation_run_id) VALUES (?,?,?,?) RETURNING reconciliation_run_id",Long.class,TEST_MONTH,stage.name(),insurer,validationRunId);
        jdbcTemplate.update("UPDATE fgc.reconciliation_run SET status='RUNNING',started_at=clock_timestamp() WHERE reconciliation_run_id=?",run);
        execution.executeInTransaction(new ReconciliationExecutionRequest(run,validationRunId,TEST_MONTH,stage,insurer,1L));
        detection.bulkCreate(run);
        Long exception = id("SELECT e.exception_case_id FROM fgc.exception_case e JOIN fgc.reconciliation_result r ON e.source_entity_id=CAST(r.reconciliation_result_id AS varchar) WHERE r.reconciliation_run_id=? AND r.contract_id=? AND e.exception_type='RECONCILIATION_MISMATCH'",run,contractId);
        jdbcTemplate.update("UPDATE fgc.validation_run SET status='RUNNING' WHERE validation_run_id=?", validationRunId);
        jdbcTemplate.update("UPDATE fgc.validation_run SET status='COMPLETED',current_step=8 WHERE validation_run_id=?", validationRunId);
        jdbcTemplate.update("INSERT INTO fgc.validation_target(validation_run_id,contract_id,product_offering_id,selection_status) SELECT ?,contract_id,product_offering_id,'SELECTED' FROM fgc.insurance_contract WHERE contract_id=?", validationRunId,contractId);
        return new Fixture(old.transactionId(),journal,run,exception,agent,item);
    }

    private ReverseAndRepostJournalCommand command(Fixture f,String amount) {
        BigDecimal value = new BigDecimal(amount);
        boolean payout = paymentStage == PaymentStage.GA_TO_FC;
        var lines = List.of(JournalLineDraft.builder().lineNo(1).accountCode(payout ? JournalAccountCode.CONFIRMED_PAYOUT_EXPENSE : JournalAccountCode.ACTUAL_RECEIVABLE).debitAmount(value).creditAmount(BigDecimal.ZERO).contractId(contractId).agentId(payout ? f.agent() : null).commissionItemId(f.item()).paymentStage(paymentStage).build(),
                JournalLineDraft.builder().lineNo(2).accountCode(payout ? JournalAccountCode.CONFIRMED_PAYOUT_PAYABLE : JournalAccountCode.ACTUAL_INCOME).debitAmount(BigDecimal.ZERO).creditAmount(value).contractId(contractId).agentId(payout ? f.agent() : null).commissionItemId(f.item()).paymentStage(paymentStage).build());
        return new ReverseAndRepostJournalCommand(new ReverseJournalCommand(f.journal(),"금액 정정","548",1L),
                JournalHeaderDraft.builder().journalType(payout ? JournalType.CONFIRMED_FC_PAYOUT : JournalType.ACTUAL_INSURER_STATEMENT).journalDate(TEST_MONTH.plusDays(14)).sourceEntityType("COMMISSION_TRANSACTION").sourceEntityId(f.payment().toString()).revisionNo(2).validationRunId(validationRunId).contractId(contractId).description("정정").lines(lines).build());
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
                ) VALUES (?, ?, ?, ?, ?, ?, 'CURRENT', ?)
                RETURNING schedule_header_id
                """, Long.class,
                contractId, paymentStage.name(), policyVersionId, scheduleVersionNo,
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
        String stage = paymentStage.name();
        String sourceType = paymentStage == PaymentStage.GA_TO_FC ? "GA_MANUAL_PAYMENT" : "INSURER_STATEMENT";
        Long insurer = id("SELECT insurer_id FROM fgc.insurance_contract WHERE contract_id=?",contractId);
        Long batch = paymentStage == PaymentStage.GA_TO_FC ? null : jdbcTemplate.queryForObject(
                "INSERT INTO fgc.statement_batch(insurer_id,settlement_month,statement_type,external_statement_no,received_on,source_ref) VALUES (?,?,'INSURER_COMMISSION',?,?,?) RETURNING statement_batch_id",Long.class,
                insurer,TEST_MONTH,UUID.randomUUID().toString(),TEST_MONTH,"548");
        LocalDate dueDate = TEST_MONTH.plusDays(14);
        Long transactionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.commission_transaction (
                    payment_stage, source_type, source_business_key, source_contract_id,
                    recipient_agent_id, commission_item_id, policy_version_id, installment_no,
                    settlement_month, due_date, amount, insurer_id, statement_batch_id, cashflow_type, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'PAYMENT', 'DRAFT')
                RETURNING commission_transaction_id
                """, Long.class,
                stage, sourceType,
                "IT-048-03:" + suffix, legacyPaymentWithoutSourceContract ? null : contractId, paymentStage == PaymentStage.GA_TO_FC ? agentId : null, commissionItemId, policyVersionId,
                installmentNo, TEST_MONTH, dueDate, new BigDecimal(amount), insurer, batch);
        Long attributionId = jdbcTemplate.queryForObject("""
                INSERT INTO fgc.transaction_attribution (
                    commission_transaction_id, attribution_seq, attribution_scope, contract_id,
                    attribution_date, attribution_month, attributed_amount,
                    inclusion_status_snapshot, attribution_method, agent_id
                ) VALUES (?, 1, 'CONTRACT', ?, ?, ?, ?, 'INCLUDED', 'DIRECT', ?)
                RETURNING transaction_attribution_id
                """, Long.class,
                transactionId, contractId, dueDate, TEST_MONTH, new BigDecimal(amount), agentId);
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
                ) VALUES (?, 1, ?, ?, 0, ?, ?, ?, ?),
                         (?, 2, ?, 0, ?, ?, ?, ?, ?)
                """,
                journalHeaderId, debitAccountId, journalAmount, contractId, agentId, paymentStage.name(), commissionItemId,
                journalHeaderId, creditAccountId, journalAmount, contractId, agentId, paymentStage.name(), commissionItemId);
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
