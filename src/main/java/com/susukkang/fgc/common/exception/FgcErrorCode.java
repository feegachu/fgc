package com.susukkang.fgc.common.exception;

import org.springframework.http.HttpStatus;

public enum FgcErrorCode {

    INVALID_REQUEST("FGC-COM-001", HttpStatus.BAD_REQUEST),
    INTERNAL_SERVER_ERROR("FGC-COM-999", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final HttpStatus status;

    FgcErrorCode(String code, HttpStatus status) {
        this.code = code;
        this.status = status;
    }

    public String getCode() {
        return code;
    }

    public HttpStatus getStatus() {
        return status;
    }
}
