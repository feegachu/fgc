package com.susukkang.fgc.journal.domain;

/**
 * 검증원장 분개유형. journal_header.journal_type CHECK 제약과 값이 같아야 한다
 *
 * #85는 1차 "생성" 대상 4종(EXPECTED_INSURER_INCOME, ACTUAL_INSURER_STATEMENT,
 * EXPECTED_FC_PAYOUT, CONFIRMED_FC_PAYOUT)의 분개 초안만 다룬다.
 */
public enum JournalType {
    EXPECTED_INSURER_INCOME,
    ACTUAL_INSURER_STATEMENT,
    EXPECTED_FC_PAYOUT,
    CONFIRMED_FC_PAYOUT,
    ADJUSTMENT,
    CLAWBACK,
    RECOVERY,
    REVERSAL
}
