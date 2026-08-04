package com.susukkang.fgc.common.web;

import java.util.List;
import java.util.Objects;

public record PageResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages,
        String sort
) {

    public PageResponse {
        Objects.requireNonNull(content, "content는 null일 수 없습니다.");

        if (page < 1) {
            throw new IllegalArgumentException("page는 1 이상이어야 합니다.");
        }

        if (size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "size는 1 이상 100 이하여야 합니다."
            );
        }

        if (totalElements < 0) {
            throw new IllegalArgumentException(
                    "totalElements는 음수일 수 없습니다."
            );
        }

        content = List.copyOf(content);
    }

    public static <T> PageResponse<T> of(
            List<T> content,
            int page,
            int size,
            long totalElements,
            String sort
    ) {
        int totalPages = totalElements == 0
                ? 0
                : (int) Math.ceil((double) totalElements / size);

        return new PageResponse<>(
                content,
                page,
                size,
                totalElements,
                totalPages,
                sort
        );
    }
}