package com.susukkang.fgc.validation.dto;

import com.susukkang.fgc.common.web.PageResponse;

import java.util.List;

/** 검증 실행 목록 조회 응답. CapCheckSearchResponse와 달리 요약 카드가 없어 page 필드만 나란히 온다. */
public record ValidationRunSearchResponse(
        List<ValidationRunItemResponse> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort
) {
    public static ValidationRunSearchResponse from(PageResponse<ValidationRunListRow> page) {
        List<ValidationRunItemResponse> content = page.content().stream()
                .map(ValidationRunItemResponse::from)
                .toList();
        return new ValidationRunSearchResponse(
                content, page.page(), page.size(), page.totalElements(), page.totalPages(), page.sort());
    }
}
