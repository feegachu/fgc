package com.susukkang.fgc.common.web;

public record ApiResponse<T>(
        T data,
        ApiError error,
        String requestId
) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                data,
                null,
                RequestIdContext.current()
        );
    }

    public static ApiResponse<Void> success() {
        return new ApiResponse<>(
                null,
                null,
                RequestIdContext.current()
        );
    }

    public static ApiResponse<Void> failure(ApiError error) {
        return new ApiResponse<>(
                null,
                error,
                RequestIdContext.current()
        );
    }
}