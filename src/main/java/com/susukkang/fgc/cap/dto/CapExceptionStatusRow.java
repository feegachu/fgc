package com.susukkang.fgc.cap.dto;

/**
 * 설명 : 한도 예외 해결 전 잠금 조회 결과
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public record CapExceptionStatusRow(
        Long exceptionCaseId,
        String status
) {
}
