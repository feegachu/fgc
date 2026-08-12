package com.susukkang.fgc.journal.domain;

/**
 * 검증원장 최소 계정과목(운영정책서 제39조). journal_account.account_code 시드값과
 * 문자열이 같아야 한다 — 이 enum의 name()을 그대로 account_code로 사용한다.
 *
 * 실제 journal_account 행(journal_account_id)은 아직 시드되어 있지 않다(#85 확인 시점
 * 기준). journal_line.journal_account_id는 FK라 영속화 단계(후속 이슈)에서는 이
 * account_code로 journal_account를 조회해 id를 채워야 한다 — 이 이슈의 산출물인
 * JournalLineDraft는 계정 "코드"까지만 표현하고 DB id 매핑은 하지 않는다.
 */
public enum JournalAccountCode {
    EXPECTED_RECEIVABLE("예상 수수료미수금", NormalBalance.DEBIT),
    EXPECTED_INCOME("예상 수수료수익 대응", NormalBalance.CREDIT),
    ACTUAL_RECEIVABLE("실제 수수료미수금", NormalBalance.DEBIT),
    ACTUAL_INCOME("실제 수수료수익 대응", NormalBalance.CREDIT),
    EXPECTED_PAYOUT_EXPENSE("예상 설계사 지급비용", NormalBalance.DEBIT),
    EXPECTED_PAYOUT_PAYABLE("예상 지급채무", NormalBalance.CREDIT),
    CONFIRMED_PAYOUT_EXPENSE("확정 설계사 지급비용", NormalBalance.DEBIT),
    CONFIRMED_PAYOUT_PAYABLE("확정 지급채무", NormalBalance.CREDIT);

    private final String description;
    private final NormalBalance normalBalance;

    JournalAccountCode(String description, NormalBalance normalBalance) {
        this.description = description;
        this.normalBalance = normalBalance;
    }

    public String description() {
        return description;
    }

    public NormalBalance normalBalance() {
        return normalBalance;
    }
}
