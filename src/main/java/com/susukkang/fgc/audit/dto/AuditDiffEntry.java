package com.susukkang.fgc.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/** 감사로그 변경 내용 비교의 한 행 (AUDT-W01). changed 인 칸만 노랗게 칠한다. */
@Schema(description = "변경 내용 비교 행 — before/after JSON 의 리프 경로 하나")
public record AuditDiffEntry(
        @Schema(description = "리프 경로. JSON 객체가 아니면 value", example = "payment.amount")
        String field,
        @Schema(description = "변경 전 값(없으면 null)", example = "500000")
        String before,
        @Schema(description = "변경 후 값(없으면 null)", example = "700000")
        String after,
        @Schema(description = "전·후 값이 다른지 여부", example = "true")
        boolean changed
) {
}
