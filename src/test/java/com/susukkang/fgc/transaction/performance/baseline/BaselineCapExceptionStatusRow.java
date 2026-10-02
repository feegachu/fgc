package com.susukkang.fgc.transaction.performance.baseline;

/* Test-only snapshot of src/main/java/com/susukkang/fgc/cap/dto/CapExceptionStatusRow.java at d1a603df.
 * Only package/type names and component registration differ; business logic is preserved. */

/**
 * 설명 : 한도 예외 해결 전 잠금 조회 결과
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public record BaselineCapExceptionStatusRow(
        Long exceptionCaseId,
        String status
) {
}
