package com.susukkang.fgc.audit.dto;

/** 감사로그 화면 행위자 필터 선택지 — 감사행을 남긴 사용자만 나열한다. */
public record AuditUserRow(
        Long userId,
        String loginId
) {
}
