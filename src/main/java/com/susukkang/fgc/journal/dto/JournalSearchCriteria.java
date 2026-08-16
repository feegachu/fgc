package com.susukkang.fgc.journal.dto;

import java.time.LocalDate;

/**
 * 검증원장 목록 조회(GET /api/v1/journals) 검색조건.
 */
public record JournalSearchCriteria(
        LocalDate from,
        LocalDate to,
        String journalType,
        String accountCode,
        Long contractId,
        String status
) {
}
