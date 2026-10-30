package com.susukkang.fgc.report.dto;

import com.susukkang.fgc.common.web.PageResponse;

/** IF-API-87 응답 data. items 는 한 페이지, summary 는 같은 필터의 전체 결과다. */
public record StatementListResponse(
        PageResponse<StatementEntry> items,
        StatementSummary summary,
        StatementBasis basis
) {
}
