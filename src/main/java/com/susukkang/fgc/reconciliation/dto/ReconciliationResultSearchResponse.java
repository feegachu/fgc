package com.susukkang.fgc.reconciliation.dto;

import com.susukkang.fgc.common.web.PageResponse;

/** IF-API-40 실행 요약과 페이징 목록 응답. */
public record ReconciliationResultSearchResponse(
        ReconciliationSummaryResponse summary,
        PageResponse<ReconciliationResultListItemResponse> items
) {
}
