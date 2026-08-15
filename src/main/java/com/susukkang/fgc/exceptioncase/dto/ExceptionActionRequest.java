package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionActionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** IF-API-44 예외 조치 요청. 처리자는 로그인 principal에서 가져오므로 요청에 포함하지 않는다. */
public record ExceptionActionRequest(
        @NotNull ExceptionActionType actionType,
        @NotBlank String reason,
        String evidenceRef
) {
}
