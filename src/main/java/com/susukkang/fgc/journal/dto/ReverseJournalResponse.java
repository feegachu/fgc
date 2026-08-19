package com.susukkang.fgc.journal.dto;

/** IF-API-36 역분개 응답. */
public record ReverseJournalResponse(
        Long journalHeaderId,
        Long reversalOfId
) {
    public static ReverseJournalResponse from(JournalCorrectionResult result) {
        return new ReverseJournalResponse(result.journalHeaderId(), result.reversalOfId());
    }
}
