package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.common.code.ExceptionStatus;

/**
 * 설명 : IF-API-36A 원장 정정 예외 생성 또는 기존 예외 반환 응답
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
public record JournalCorrectionExceptionResponse(
        Long exceptionCaseId,
        boolean created,
        ExceptionStatus status,
        String redirectUrl
) {
    public static JournalCorrectionExceptionResponse from(
            JournalCorrectionExceptionRow row,
            boolean created
    ) {
        return new JournalCorrectionExceptionResponse(
                row.exceptionCaseId(),
                created,
                row.status(),
                "/exceptions?selected=" + row.exceptionCaseId());
    }
}
