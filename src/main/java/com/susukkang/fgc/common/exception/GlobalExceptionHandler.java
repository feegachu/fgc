package com.susukkang.fgc.common.exception;

import com.susukkang.fgc.common.web.ApiResponse;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final String INVALID_AS_OF_MESSAGE =
            "기준일자(asOf)는 yyyy-MM-dd 형식이어야 합니다.";

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(
            MethodArgumentTypeMismatchException exception
    ) {
        String message = "asOf".equals(exception.getName())
                ? INVALID_AS_OF_MESSAGE
                : "요청 파라미터 형식이 올바르지 않습니다.";
        return invalidRequest(message);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiResponse<Void>> handleMissingParameter(
            MissingServletRequestParameterException exception
    ) {
        String message = "asOf".equals(exception.getParameterName())
                ? "기준일자(asOf)는 필수입니다. yyyy-MM-dd 형식으로 입력하세요."
                : "필수 요청 파라미터가 누락되었습니다: " + exception.getParameterName();
        return invalidRequest(message);
    }

    private ResponseEntity<ApiResponse<Void>> invalidRequest(String message) {
        FgcErrorCode errorCode = FgcErrorCode.INVALID_REQUEST;
        return ResponseEntity.status(errorCode.getStatus())
                .body(ApiResponse.failure(errorCode.getCode(), message));
    }
}
