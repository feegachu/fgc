package com.susukkang.fgc.journal.dto;

import java.time.LocalDate;

/**
 * 검증원장 목록 조회(GET /api/v1/journals) 검색조건.
 *
 * <p>sort 는 {@code key,asc|desc} 형식이며(#604) 비어 있으면 분개일 내림차순이다.
 */
public record JournalSearchCriteria(
        LocalDate from,
        LocalDate to,
        String journalType,
        String accountCode,
        Long contractId,
        String contractNo,
        String status,
        String sort
) {

    /** 정렬 없이 검색하는 기존 호출용 생성자. */
    public JournalSearchCriteria(LocalDate from, LocalDate to, String journalType,
                                 String accountCode, Long contractId, String contractNo, String status) {
        this(from, to, journalType, accountCode, contractId, contractNo, status, null);
    }

    /** 계약번호 조건 없이 검색하는 기존 호출용 생성자. */
    public JournalSearchCriteria(LocalDate from, LocalDate to, String journalType,
                                 String accountCode, Long contractId, String status) {
        this(from, to, journalType, accountCode, contractId, null, status, null);
    }
}
