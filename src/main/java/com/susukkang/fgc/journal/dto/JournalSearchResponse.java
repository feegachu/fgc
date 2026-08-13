package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.common.web.PageResponse;

import java.util.List;

/** 검증원장 목록 조회 응답. ValidationRunSearchResponse와 같은 페이지 래핑 관례. */
public record JournalSearchResponse(
        List<JournalItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort
) {
    public static JournalSearchResponse from(PageResponse<JournalListRow> page) {
        List<JournalItemResponse> content = page.content().stream()
                .map(JournalItemResponse::from)
                .toList();
        return new JournalSearchResponse(
                content, page.page(), page.size(), page.totalElements(), page.totalPages(), page.sort());
    }
}
