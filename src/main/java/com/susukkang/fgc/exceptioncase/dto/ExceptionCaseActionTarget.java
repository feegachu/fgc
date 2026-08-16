package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionStatus;

/** 처리 직전 SELECT FOR UPDATE로 잠그는 exception_case의 최소 상태. */
public record ExceptionCaseActionTarget(
        Long exceptionCaseId,
        ExceptionStatus status,
        Long assignedTo
) {
}
