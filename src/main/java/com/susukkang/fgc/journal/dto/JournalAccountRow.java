package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.journal.entity.JournalAccount;
import lombok.Getter;
import lombok.Setter;

/**
 * journal_account 1행 조회 결과(#93). JournalAccountCode로 활성 계정과목을 찾을 때 씀
 */
@Getter
@Setter
public class JournalAccountRow {
    private Long journalAccountId;
    private String accountCode;
    private String accountName;
    private String normalBalance;
    private Boolean activeYn;

    public static JournalAccountRow from(JournalAccount account) {
        JournalAccountRow row = new JournalAccountRow();
        row.setJournalAccountId(account.getJournalAccountId());
        row.setAccountCode(account.getAccountCode());
        row.setAccountName(account.getAccountName());
        row.setNormalBalance(account.getNormalBalance().name());
        row.setActiveYn(account.isActiveYn());
        return row;
    }
}
