package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionStatus;

/**
 * 설명 : 처리 직전 SELECT FOR UPDATE로 잠그는 exception_case의 최소 상태
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
public record ExceptionCaseActionTarget(
        Long exceptionCaseId,
        String exceptionType,
        ExceptionStatus status,
        Long assignedTo
) {
}
