package com.susukkang.fgc.journal.domain;

/**
 * 검증원장 분개유형. journal_header.journal_type CHECK 제약과 값이 같아야 한다
 * (V1__baseline_v2_1_2.sql:1199-1203, 운영정책서 제38조).
 *
 * #85는 1차 "생성" 대상 4종(EXPECTED_INSURER_INCOME, ACTUAL_INSURER_STATEMENT,
 * EXPECTED_FC_PAYOUT, CONFIRMED_FC_PAYOUT)의 분개 초안만 다룬다. REVERSAL은 FUN-047
 * (원장 정정·역분개)의 몫이고, ADJUSTMENT/CLAWBACK/RECOVERY는 2차 활성화 대상이라
 * 이 이슈에서 생성 로직을 만들지 않는다 — 다만 DB CHECK 제약과 1:1로 맞춰 두어야
 * journal_header INSERT 시점에 이 enum이 항상 유효한 값만 표현하도록 값 자체는 8개를
 * 모두 정의해 둔다.
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
