package com.susukkang.fgc.common.web;

import java.util.Map;

public record ApiError(
        String code,
        String message,
        String field,
        Map<String, Object> params,
        String detail
) {
    public ApiError {
        params = params == null ? Map.of() : Map.copyOf(params);
    }
}