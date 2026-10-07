package com.susukkang.fgc.audit.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** AUDT-W01 필터 선택지(#407) — 1차 화면이 모델로 받던 actionCodes·entityTypes·auditUsers. */
@Schema(description = "감사로그 필터 선택지")
public record AuditLogOptionsResponse(
        @Schema(description = "행위 종류(감사행에 기록된 값, 정렬)")
        List<String> actionCodes,
        @Schema(description = "대상 종류(감사행에 기록된 값, 정렬)")
        List<String> entityTypes,
        @Schema(description = "행위자(감사행을 남긴 사용자, 로그인 ID 순)")
        List<AuditUserRow> users
) {
}
