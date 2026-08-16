package com.susukkang.fgc.exceptioncase.dto;

import java.util.List;

/** IF-API-43 응답: 유형별 요약 카드와 페이징된 예외 목록. */
public record ExceptionCaseSearchResponse(
        List<ExceptionTypeSummaryResponse> summary,
        List<ExceptionCaseResponseDTO> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort
) {
    public ExceptionCaseSearchResponse {
        summary = List.copyOf(summary);
        content = List.copyOf(content);
    }
}
