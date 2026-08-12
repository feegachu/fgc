package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.domain.ActualInsurerStatementJournalCommand;
import com.susukkang.fgc.journal.domain.ConfirmedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;

/**
 * #85 복식부기 분개 생성. 예상 스케줄(schedule_line)과 실제 명세·확정 지급 건
 * (commission_transaction)으로부터 균형 잡힌(차변합계=대변합계) 분개 초안을 만든다.
 *
 * DB 조회·저장은 하지 않는다 — 호출자가 넘겨준 Command 값만으로 계산하는 순수 도메인
 * 변환이다. journal_header/journal_line INSERT, journal_no 채번, journal_account_id
 * FK 매핑, 균형검사 후 POSTED 전이는 영속화 서비스(후속 이슈)의 몫이다.
 */
public interface JournalEntryDraftService {

    /**
     * EXPECTED_INSURER_INCOME 분개 초안을 만든다.
     * 차변 EXPECTED_RECEIVABLE / 대변 EXPECTED_INCOME (운영정책서 제39조).
     */
    JournalHeaderDraft draftExpectedInsurerIncome(ExpectedInsurerIncomeJournalCommand command);

    /**
     * ACTUAL_INSURER_STATEMENT 분개 초안을 만든다.
     * 차변 ACTUAL_RECEIVABLE / 대변 ACTUAL_INCOME (운영정책서 제39조).
     */
    JournalHeaderDraft draftActualInsurerStatement(ActualInsurerStatementJournalCommand command);

    /**
     * EXPECTED_FC_PAYOUT 분개 초안을 만든다.
     * 차변 EXPECTED_PAYOUT_EXPENSE / 대변 EXPECTED_PAYOUT_PAYABLE (운영정책서 제39조).
     */
    JournalHeaderDraft draftExpectedFcPayout(ExpectedFcPayoutJournalCommand command);

    /**
     * CONFIRMED_FC_PAYOUT 분개 초안을 만든다.
     * 차변 CONFIRMED_PAYOUT_EXPENSE / 대변 CONFIRMED_PAYOUT_PAYABLE (운영정책서 제39조).
     */
    JournalHeaderDraft draftConfirmedFcPayout(ConfirmedFcPayoutJournalCommand command);
}
