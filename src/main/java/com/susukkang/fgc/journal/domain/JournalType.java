package com.susukkang.fgc.journal.domain;

/**
 * 검증원장 분개유형. journal_header.journal_type CHECK 제약과 값이 같아야 한다
 *
 * 1차 "생성" 대상 4종(EXPECTED_INSURER_INCOME, ACTUAL_INSURER_STATEMENT,
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
    REVERSAL;

    /** 화면정의서 "분개유형 8종" 표(docs/FGC_화면정의서_v2_0.md:1118-1129)와 일치시킨다. */
    public String label() {
        return switch (this) {
            case EXPECTED_INSURER_INCOME -> "예상 수입(원수사→GA)";
            case ACTUAL_INSURER_STATEMENT -> "실제 명세(원수사)";
            case EXPECTED_FC_PAYOUT -> "예상 지급(GA→설계사)";
            case CONFIRMED_FC_PAYOUT -> "확정 지급(GA→설계사)";
            case ADJUSTMENT -> "조정";
            case CLAWBACK -> "환수";
            case RECOVERY -> "회수";
            case REVERSAL -> "역분개";
        };
    }
}
