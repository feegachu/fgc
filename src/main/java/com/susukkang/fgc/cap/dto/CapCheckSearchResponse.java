package com.susukkang.fgc.cap.dto;

import java.util.List;

/** IF-API-30 응답. 카드·전체범위 집계와 페이징 목록을 같은 검색 응답으로 제공한다. */
public record CapCheckSearchResponse(
        CapCheckSummaryResponse summary,
        List<CapStageSummaryResponse> stageSummary,
        List<CapAgentSummaryResponse> agentSummary,
        List<CapCheckItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort
) {
    public static CapCheckSearchResponse from(CapCheckSearchResult result) {
        List<CapCheckItemResponse> content = result.page().content().stream()
                .map(CapCheckItemResponse::from)
                .toList();
        return new CapCheckSearchResponse(
                CapCheckSummaryResponse.from(result.summary()),
                result.stageSummary().stream().map(CapStageSummaryResponse::from).toList(),
                result.agentSummary().stream().map(CapAgentSummaryResponse::from).toList(),
                content,
                result.page().page(), result.page().size(), result.page().totalElements(),
                result.page().totalPages(), result.page().sort());
    }
}
