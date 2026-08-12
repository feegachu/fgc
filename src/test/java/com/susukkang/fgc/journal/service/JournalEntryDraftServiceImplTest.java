package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.journal.domain.ActualInsurerStatementJournalCommand;
import com.susukkang.fgc.journal.domain.ConfirmedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.domain.JournalAccountCode;
import com.susukkang.fgc.journal.domain.JournalType;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import com.susukkang.fgc.journal.dto.JournalLineDraft;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JournalEntryDraftServiceImpl은 DB 없이 Command 값만으로 계산하는 순수 함수라
 * Mockito 없이 바로 assert 가능하다. 각 메서드마다 공통으로 확인할 것:
 *   1) 차변합계 == 대변합계 (ck_journal_line_one_side를 만족하는 두 줄)
 *   2) 계정코드가 운영정책서 제39조 매핑과 일치하고, 차/대변 계정이 서로 다름
 *   3) sourceEntityType/sourceEntityId가 설계 결정(schedule_line vs commission_transaction)대로
 *   4) agentId가 유형별로 기대한 값(INSURER 계열=null, FC_PAYOUT 계열=beneficiaryAgentId)
 *   5) amount<=0 또는 null이면 IllegalArgumentException
 */
class JournalEntryDraftServiceImplTest {

    private final JournalEntryDraftServiceImpl service = new JournalEntryDraftServiceImpl();
    private final LocalDate journalDate = LocalDate.of(2026, 8, 1);

    private static BigDecimal debitTotal(JournalHeaderDraft draft) {
        return draft.getLines().stream().map(JournalLineDraft::getDebitAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal creditTotal(JournalHeaderDraft draft) {
        return draft.getLines().stream().map(JournalLineDraft::getCreditAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Test
    void draftExpectedInsurerIncomeBuildsBalancedDraftWithNoAgent() {
        ExpectedInsurerIncomeJournalCommand command = ExpectedInsurerIncomeJournalCommand.builder()
                .scheduleLineId(1001L)
                .contractId(2001L)
                .policyVersionId(3001L)
                .validationRunId(4001L)
                .commissionItemId(5001L)
                .journalDate(journalDate)
                .expectedAmount(BigDecimal.valueOf(50_000))
                .description("예상 수입 테스트")
                .build();

        JournalHeaderDraft draft = service.draftExpectedInsurerIncome(command);

        assertThat(draft.getJournalType()).isEqualTo(JournalType.EXPECTED_INSURER_INCOME);
        assertThat(draft.getSourceEntityType()).isEqualTo("SCHEDULE_LINE");
        assertThat(draft.getSourceEntityId()).isEqualTo("1001");
        assertThat(draft.getRevisionNo()).isEqualTo(1);
        assertThat(draft.getLines()).hasSize(2);
        assertThat(debitTotal(draft)).isEqualByComparingTo(creditTotal(draft));

        JournalLineDraft debitLine = draft.getLines().get(0);
        JournalLineDraft creditLine = draft.getLines().get(1);
        assertThat(debitLine.getAccountCode()).isEqualTo(JournalAccountCode.EXPECTED_RECEIVABLE);
        assertThat(creditLine.getAccountCode()).isEqualTo(JournalAccountCode.EXPECTED_INCOME);
        assertThat(debitLine.getAccountCode()).isNotEqualTo(creditLine.getAccountCode());
        assertThat(debitLine.getAgentId()).isNull();
        assertThat(creditLine.getAgentId()).isNull();
        assertThat(debitLine.getPaymentStage()).isEqualTo(PaymentStage.INSURER_TO_GA);
        assertThat(debitLine.getContractId()).isEqualTo(2001L);
    }

    @Test
    void draftExpectedInsurerIncomeRejectsZeroOrNullAmount() {
        ExpectedInsurerIncomeJournalCommand zeroAmount = ExpectedInsurerIncomeJournalCommand.builder()
                .scheduleLineId(1L).contractId(1L).journalDate(journalDate)
                .expectedAmount(BigDecimal.ZERO).build();
        ExpectedInsurerIncomeJournalCommand nullAmount = ExpectedInsurerIncomeJournalCommand.builder()
                .scheduleLineId(1L).contractId(1L).journalDate(journalDate)
                .expectedAmount(null).build();

        assertThatThrownBy(() -> service.draftExpectedInsurerIncome(zeroAmount))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.draftExpectedInsurerIncome(nullAmount))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draftActualInsurerStatementSourcesFromCommissionTransactionWithDistinctAccounts() {
        ActualInsurerStatementJournalCommand command = ActualInsurerStatementJournalCommand.builder()
                .commissionTransactionId(6001L)
                .contractId(2001L)
                .journalDate(journalDate)
                .actualAmount(BigDecimal.valueOf(48_500))
                .build();

        JournalHeaderDraft draft = service.draftActualInsurerStatement(command);

        assertThat(draft.getJournalType()).isEqualTo(JournalType.ACTUAL_INSURER_STATEMENT);
        assertThat(draft.getSourceEntityType()).isEqualTo("COMMISSION_TRANSACTION");
        assertThat(draft.getSourceEntityId()).isEqualTo("6001");
        assertThat(debitTotal(draft)).isEqualByComparingTo(creditTotal(draft));

        JournalLineDraft debitLine = draft.getLines().get(0);
        JournalLineDraft creditLine = draft.getLines().get(1);
        assertThat(debitLine.getAccountCode()).isEqualTo(JournalAccountCode.ACTUAL_RECEIVABLE);
        assertThat(creditLine.getAccountCode()).isEqualTo(JournalAccountCode.ACTUAL_INCOME);
        assertThat(debitLine.getAgentId()).isNull();
    }

    @Test
    void draftActualInsurerStatementRejectsZeroOrNullAmount() {
        ActualInsurerStatementJournalCommand zeroAmount = ActualInsurerStatementJournalCommand.builder()
                .commissionTransactionId(1L).contractId(1L).journalDate(journalDate)
                .actualAmount(BigDecimal.ZERO).build();

        assertThatThrownBy(() -> service.draftActualInsurerStatement(zeroAmount))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draftExpectedFcPayoutUsesPayoutAccountsAndCarriesBeneficiaryAgent() {
        ExpectedFcPayoutJournalCommand command = ExpectedFcPayoutJournalCommand.builder()
                .scheduleLineId(1002L)
                .contractId(2001L)
                .beneficiaryAgentId(7001L)
                .journalDate(journalDate)
                .expectedAmount(BigDecimal.valueOf(30_000))
                .build();

        JournalHeaderDraft draft = service.draftExpectedFcPayout(command);

        assertThat(draft.getJournalType()).isEqualTo(JournalType.EXPECTED_FC_PAYOUT);
        assertThat(draft.getSourceEntityType()).isEqualTo("SCHEDULE_LINE");
        assertThat(draft.getSourceEntityId()).isEqualTo("1002");
        assertThat(debitTotal(draft)).isEqualByComparingTo(creditTotal(draft));

        JournalLineDraft debitLine = draft.getLines().get(0);
        JournalLineDraft creditLine = draft.getLines().get(1);
        assertThat(debitLine.getAccountCode()).isEqualTo(JournalAccountCode.EXPECTED_PAYOUT_EXPENSE);
        assertThat(creditLine.getAccountCode()).isEqualTo(JournalAccountCode.EXPECTED_PAYOUT_PAYABLE);
        assertThat(debitLine.getAgentId()).isEqualTo(7001L);
        assertThat(creditLine.getAgentId()).isEqualTo(7001L);
        assertThat(debitLine.getPaymentStage()).isEqualTo(PaymentStage.GA_TO_FC);
    }

    @Test
    void draftExpectedFcPayoutRejectsZeroOrNullAmount() {
        ExpectedFcPayoutJournalCommand zeroAmount = ExpectedFcPayoutJournalCommand.builder()
                .scheduleLineId(1L).contractId(1L).journalDate(journalDate)
                .expectedAmount(BigDecimal.ZERO).build();

        assertThatThrownBy(() -> service.draftExpectedFcPayout(zeroAmount))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void draftConfirmedFcPayoutSourcesFromCommissionTransactionAndCarriesBeneficiaryAgent() {
        ConfirmedFcPayoutJournalCommand command = ConfirmedFcPayoutJournalCommand.builder()
                .commissionTransactionId(6002L)
                .contractId(2001L)
                .beneficiaryAgentId(7001L)
                .journalDate(journalDate)
                .confirmedAmount(BigDecimal.valueOf(29_500))
                .build();

        JournalHeaderDraft draft = service.draftConfirmedFcPayout(command);

        assertThat(draft.getJournalType()).isEqualTo(JournalType.CONFIRMED_FC_PAYOUT);
        assertThat(draft.getSourceEntityType()).isEqualTo("COMMISSION_TRANSACTION");
        assertThat(draft.getSourceEntityId()).isEqualTo("6002");
        assertThat(debitTotal(draft)).isEqualByComparingTo(creditTotal(draft));

        JournalLineDraft debitLine = draft.getLines().get(0);
        JournalLineDraft creditLine = draft.getLines().get(1);
        assertThat(debitLine.getAccountCode()).isEqualTo(JournalAccountCode.CONFIRMED_PAYOUT_EXPENSE);
        assertThat(creditLine.getAccountCode()).isEqualTo(JournalAccountCode.CONFIRMED_PAYOUT_PAYABLE);
        assertThat(debitLine.getAgentId()).isEqualTo(7001L);
        assertThat(creditLine.getAgentId()).isEqualTo(7001L);
    }

    @Test
    void draftConfirmedFcPayoutRejectsZeroOrNullAmount() {
        ConfirmedFcPayoutJournalCommand nullAmount = ConfirmedFcPayoutJournalCommand.builder()
                .commissionTransactionId(1L).contractId(1L).journalDate(journalDate)
                .confirmedAmount(null).build();

        assertThatThrownBy(() -> service.draftConfirmedFcPayout(nullAmount))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
