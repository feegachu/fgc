package com.susukkang.fgc.common.exception;

import lombok.Getter;

import java.util.Map;
import java.util.Objects;

@Getter

/**
 * Service에서 발생한 업무 규칙 위반을 전달하는 예외다.
 *
 * FgcErrorCode와 메시지 치환 파라미터를 GlobalExceptionHandler로
 * 전달하며, HTTP 응답 생성은 담당하지 않는다.
 */
public class FgcBusinessException extends RuntimeException {

    private final FgcErrorCode errorCode;
    private final String field;
    private final Map<String, Object> params;
    private final String detail;

    public FgcBusinessException(FgcErrorCode errorCode) {
        this(errorCode, null, Map.of(), null);
    }

    public FgcBusinessException(
            FgcErrorCode errorCode,
            Map<String, Object> params
    ) {
        this(errorCode, null, params, null);
    }

    public FgcBusinessException(
            FgcErrorCode errorCode,
            String field,
            Map<String, Object> params,
            String detail
    ) {
        super(Objects.requireNonNull(errorCode).getCode());

        this.errorCode = errorCode;
        this.field = field;
        this.params = params == null ? Map.of() : Map.copyOf(params);
        this.detail = detail;
    }

}