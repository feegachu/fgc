package com.susukkang.fgc.cap.dto;

import java.util.List;

/** IF-API-30 응답. summary(카드 4장)와 page 필드가 같은 레벨에 나란히 온다(인터페이스정의서 4-2절). */
public record CapCheckSearchResponse(
        CapCheckSummaryResponse summary,
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
                content,
                result.page().page(), result.page().size(), result.page().totalElements(),
                result.page().totalPages(), result.page().sort());
    }
}
