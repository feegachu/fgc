package com.susukkang.fgc.journal.service;

import com.susukkang.fgc.journal.domain.ActualInsurerStatementJournalCommand;
import com.susukkang.fgc.journal.domain.ConfirmedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedFcPayoutJournalCommand;
import com.susukkang.fgc.journal.domain.ExpectedInsurerIncomeJournalCommand;
import com.susukkang.fgc.journal.dto.JournalHeaderDraft;
import org.springframework.stereotype.Service;

/**
 * TODO(#85): 아래 4개 메서드를 직접 구현한다. 공통 골격은 같다 —
 *
 *   1. amount(expectedAmount/actualAmount/confirmedAmount)가 null이거나 0 이하면
 *      IllegalArgumentException(또는 프로젝트 공통 검증 예외)을 던진다 — 0 이하 금액으로
 *      분개를 만들면 ck_journal_line_one_side를 만족하는 두 줄을 만들 수 없다.
 *   2. journal_line을 정확히 2줄(차변 1줄, 대변 1줄) 만든다. lineNo는 1, 2 — 나중에 줄이
 *      추가돼도(예: 세금 별도 계상) uq_journal_line(journal_header_id, line_no) 순서만
 *      지키면 된다.
 *   3. 차변 줄 debitAmount = amount, creditAmount = 0. 대변 줄은 반대 —
 *      ck_journal_line_one_side(한쪽만 0보다 큼)를 반드시 지킨다.
 *   4. 각 메서드가 사용할 계정코드는 인터페이스 Javadoc과 운영정책서 제39조에 이미
 *      명시돼 있다 — draftExpectedInsurerIncome은 EXPECTED_RECEIVABLE/EXPECTED_INCOME,
 *      draftActualInsurerStatement는 ACTUAL_RECEIVABLE/ACTUAL_INCOME,
 *      draftExpectedFcPayout은 EXPECTED_PAYOUT_EXPENSE/EXPECTED_PAYOUT_PAYABLE,
 *      draftConfirmedFcPayout은 CONFIRMED_PAYOUT_EXPENSE/CONFIRMED_PAYOUT_PAYABLE.
 *   5. journal_line.agent_id: INSURER 계열(EXPECTED_INSURER_INCOME,
 *      ACTUAL_INSURER_STATEMENT)은 GA 레벨이라 두 줄 모두 agentId=null. FC_PAYOUT
 *      계열(EXPECTED_FC_PAYOUT, CONFIRMED_FC_PAYOUT)은 두 줄 모두
 *      command.getBeneficiaryAgentId()를 채운다.
 *   6. journal_line.paymentStage: INSURER 계열은 PaymentStage.INSURER_TO_GA,
 *      FC_PAYOUT 계열은 PaymentStage.GA_TO_FC — 두 줄 모두 같은 값.
 *   7. journal_line.contractId/commissionItemId는 command 값을 두 줄에 그대로 복사한다.
 *   8. JournalHeaderDraft.sourceEntityType은 스케줄 기반(EXPECTED_*) 이면
 *      "SCHEDULE_LINE", 명세/지급 건 기반(ACTUAL_INSURER_STATEMENT,
 *      CONFIRMED_FC_PAYOUT) 이면 "COMMISSION_TRANSACTION" — sourceEntityId는 해당
 *      command의 scheduleLineId 또는 commissionTransactionId를 String.valueOf()로
 *      변환한다(source_entity_id 컬럼이 varchar).
 *   9. JournalHeaderDraft.revisionNo는 항상 1로 고정한다(재기표는 FUN-047 범위).
 *  10. JournalHeaderDraft.validationRunId/contractId/policyVersionId/description은
 *      command 값을 그대로 옮긴다.
 *
 * 단위 테스트는 4개 메서드 각각에 대해 (a) 정상 케이스에서 차변합계=대변합계인지,
 * (b) amount<=0일 때 예외가 나는지, (c) agent_id/paymentStage가 유형별로 기대한 값인지
 * 검증하면 된다 — DB를 쓰지 않는 순수 함수라 Mockito 없이 바로 assert 가능하다.
 */
@Service
public class JournalEntryDraftServiceImpl implements JournalEntryDraftService {

    @Override
    public JournalHeaderDraft draftExpectedInsurerIncome(ExpectedInsurerIncomeJournalCommand command) {
        throw new UnsupportedOperationException("TODO(#85): 위 클래스 Javadoc의 공통 골격대로 구현");
    }

    @Override
    public JournalHeaderDraft draftActualInsurerStatement(ActualInsurerStatementJournalCommand command) {
        throw new UnsupportedOperationException("TODO(#85): 위 클래스 Javadoc의 공통 골격대로 구현");
    }

    @Override
    public JournalHeaderDraft draftExpectedFcPayout(ExpectedFcPayoutJournalCommand command) {
        throw new UnsupportedOperationException("TODO(#85): 위 클래스 Javadoc의 공통 골격대로 구현");
    }

    @Override
    public JournalHeaderDraft draftConfirmedFcPayout(ConfirmedFcPayoutJournalCommand command) {
        throw new UnsupportedOperationException("TODO(#85): 위 클래스 Javadoc의 공통 골격대로 구현");
    }
}
