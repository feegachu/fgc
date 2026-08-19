package com.susukkang.fgc.reconciliation.dto;

import com.susukkang.fgc.common.web.PageResponse;

import java.util.List;

/** IF-API-39 대사 실행 이력 조회 응답. ValidationRunSearchResponse/JournalSearchResponse와 같은 페이지 래핑 관례. */
public record ReconciliationRunSearchResponse(
        List<ReconciliationRunHistoryResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort
) {
    public static ReconciliationRunSearchResponse from(PageResponse<ReconciliationRunHistoryResponse> page) {
        return new ReconciliationRunSearchResponse(
                page.content(), page.page(), page.size(), page.totalElements(), page.totalPages(), page.sort());
    }
}
