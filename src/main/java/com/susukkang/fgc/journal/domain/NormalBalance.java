package com.susukkang.fgc.journal.domain;

/**
 * journal_account.normal_balance CHECK 제약과 값이 같아야 한다
 * (V1__baseline_v2_1_2.sql:1189 — CHECK (normal_balance IN ('DEBIT','CREDIT'))).
 */
public enum NormalBalance {
    DEBIT,
    CREDIT
}
