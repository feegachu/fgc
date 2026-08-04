package com.susukkang.fgc.common.web;

import java.util.UUID;

public record ApiResponse<T>(T data, Error error, String requestId) {

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(data, null, newRequestId());
    }

    public static <T> ApiResponse<T> failure(String code, String message) {
        return new ApiResponse<>(null, new Error(code, message), newRequestId());
    }

    private static String newRequestId() {
        return UUID.randomUUID().toString();
    }

    public record Error(String code, String message) {
    }
}
