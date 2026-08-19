package com.susukkang.fgc.journal.dto;

/** FUN-047 역분개 명령. 처리자는 인증 principal에서 전달한다. */
public record ReverseJournalCommand(
        Long journalHeaderId,
        String reason,
        String evidenceRef,
        Long requestedBy
) {
}
