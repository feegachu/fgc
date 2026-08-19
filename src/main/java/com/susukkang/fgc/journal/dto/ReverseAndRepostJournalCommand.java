package com.susukkang.fgc.journal.dto;

/** FUN-047 원분개 역분개와 정정 초안 재기표를 한 트랜잭션으로 처리하는 내부 명령. */
public record ReverseAndRepostJournalCommand(
        ReverseJournalCommand reversal,
        JournalHeaderDraft correctedDraft
) {
}
