package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionStatus;

/**
 * 설명 : IF-API-44A 실행 전 잠근 예외의 상태와 원분개 참조
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
public record JournalCorrectionExceptionTarget(
        Long exceptionCaseId,
        String exceptionType,
        ExceptionStatus status,
        String sourceEntityType,
        String sourceEntityId
) {
}
