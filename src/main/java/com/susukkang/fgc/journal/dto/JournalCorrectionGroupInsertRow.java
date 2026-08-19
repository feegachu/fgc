package com.susukkang.fgc.journal.dto;

import lombok.Builder;

/** journal_correction_group INSERT 파라미터. */
@Builder
public record JournalCorrectionGroupInsertRow(
        String correctionGroupKey,
        Long originalJournalHeaderId,
        String reason,
        String evidenceRef,
        Long createdBy
) {
}
