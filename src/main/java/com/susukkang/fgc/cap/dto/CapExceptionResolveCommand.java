package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.ExceptionActionType;
import lombok.Builder;

/**
 * 설명 : 한도 예외 해결조치와 처리 근거를 저장하기 위한 명령
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Builder
public record CapExceptionResolveCommand(
        Long exceptionCaseId,
        ExceptionActionType actionType,
        String reason,
        String evidenceRef,
        Long actionBy
) {
}
