package com.susukkang.fgc.journal.domain;

/**
 * 검증원장 최소 계정과목
 * journal_account.account_code 시드값과 문자열이 같아야 한다
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
