package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.domain.ActualInsurerStatementJournalCommand;
import com.susukkang.fgc.journal.domain.ConfirmedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;

/**
 * FUN-046 복식부기 분개 생성
 * 예상 스케줄(schedule_line)과 실제 명세·확정 지급 건
 * (commission_transaction)으로부터 균형 잡힌(차변합계=대변합계) 분개 초안을 만듬
 */
public interface JournalEntryDraftService {

    /**
     * EXPECTED_INSURER_INCOME 분개 초안을 만든다.
     * 차변 EXPECTED_RECEIVABLE / 대변 EXPECTED_INCOME
     */
    JournalHeaderDraft draftExpectedInsurerIncome(ExpectedInsurerIncomeJournalCommand command);

    /**
     * ACTUAL_INSURER_STATEMENT 분개 초안을 만든다.
     * 차변 ACTUAL_RECEIVABLE / 대변 ACTUAL_INCOME
     */
    JournalHeaderDraft draftActualInsurerStatement(ActualInsurerStatementJournalCommand command);

    /**
     * EXPECTED_FC_PAYOUT 분개 초안을 만든다.
     * 차변 EXPECTED_PAYOUT_EXPENSE / 대변 EXPECTED_PAYOUT_PAYABLE
     */
    JournalHeaderDraft draftExpectedFcPayout(ExpectedFcPayoutJournalCommand command);

    /**
     * CONFIRMED_FC_PAYOUT 분개 초안을 만든다.
     * 차변 CONFIRMED_PAYOUT_EXPENSE / 대변 CONFIRMED_PAYOUT_PAYABLE
     */
    JournalHeaderDraft draftConfirmedFcPayout(ConfirmedFcPayoutJournalCommand command);
}
