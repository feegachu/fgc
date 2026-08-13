package com.susukkang.fgc.common.code;

/**
 * journal_header.status CHECK 제약과 값이 같아야 한다
 * (CHECK (status IN ('DRAFT','POSTED','REVERSED')), V1__baseline_v2_1_2.sql:1212-1213).
 */
public enum JournalHeaderStatus {
    DRAFT,
    POSTED,
    REVERSED;

    /** 화면정의서 "상태 | 작성중 / 기표됨 / 역분개됨"(docs/FGC_화면정의서_v2_0.md:1115)과 일치시킨다. */
    public String label() {
        return switch (this) {
            case DRAFT -> "작성중";
            case POSTED -> "기표됨";
            case REVERSED -> "역분개됨";
        };
    }
}
