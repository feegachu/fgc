package com.susukkang.fgc.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** AUDT-W01 상세 — 감사행과 서버가 계산한 변경 내용 비교(#407). */
@Schema(description = "감사로그 상세 응답")
public record AuditLogDetailResponse(
        @Schema(description = "감사로그")
        AuditLogResponse log,
        @Schema(description = "before/after 리프 경로 비교. 둘 다 없으면 빈 배열")
        List<AuditDiffEntry> diff
) {
}
