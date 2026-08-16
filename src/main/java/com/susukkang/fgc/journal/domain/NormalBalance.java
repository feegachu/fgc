package com.susukkang.fgc.journal.domain;

/**
 * journal_account.normal_balance CHECK 제약과 값이 같아야 한다
 * (V1__baseline_v2_1_2.sql:1189 — CHECK (normal_balance IN ('DEBIT','CREDIT'))).
 */
public enum NormalBalance {
    DEBIT,
    CREDIT;

    /** 운영정책서 제39조 "최소 계정과목" 표(docs/FGC_가상_GA_운영정책서_v1_0.md:881-890)와 일치시킨다. */
    public String label() {
        return switch (this) {
            case DEBIT -> "차변";
            case CREDIT -> "대변";
        };
    }
}
