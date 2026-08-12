package com.susukkang.fgc.journal.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * journal_account 1행 조회 결과(#93). JournalAccountCode(enum, #85)로 활성 계정과목을
 * 찾을 때 쓴다 — journalAccountId가 journal_line.journal_account_id FK에 들어간다.
 */
@Getter
@Setter
public class JournalAccountRow {
    private Long journalAccountId;
    private String accountCode;
    private String accountName;
    private String normalBalance;
    private Boolean activeYn;
}
