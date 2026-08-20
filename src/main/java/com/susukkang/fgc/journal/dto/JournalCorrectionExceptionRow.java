package com.susukkang.fgc.journal.dto;

import com.susukkang.fgc.common.code.ExceptionStatus;

/**
 * 설명 : 원분개 업무키로 조회한 원장 정정 예외의 최소 상태
 *
 * @author yslee
 * @since 2026-08-20
 * @version 1.2
 */
public record JournalCorrectionExceptionRow(
        Long exceptionCaseId,
        ExceptionStatus status
) {
}
