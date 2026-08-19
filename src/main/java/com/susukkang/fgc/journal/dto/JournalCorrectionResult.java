package com.susukkang.fgc.journal.dto;

/** 역분개 또는 역분개+재기표 처리 결과. repostedJournalHeaderId는 기본 역분개에서 null이다. */
public record JournalCorrectionResult(
        Long journalHeaderId,
        Long reversalOfId,
        Long repostedJournalHeaderId,
        String correctionGroupKey
) {
}
