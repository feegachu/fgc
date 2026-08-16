package com.susukkang.fgc.audit.dto;

import lombok.Builder;
import lombok.Getter;

// FUN-001 개발 순서 8

/**
 * audit_log INSERT 파라미터. audit_log는 append-only 다(trg_audit_log_append_only).
 *
 * entityType/entityId/actionCode 는 NOT NULL 이다.
 * beforeValue/afterValue는 변경 전후 값을 JSON 문자열로 전달하며 null을 허용한다.
 */
@Getter
@Builder
public class AuditLogInsertRow {
    private final Long userId;
    private final String actionCode;
    private final String entityType;
    private final String entityId;
    private final String beforeValue;
    private final String afterValue;
    private final String reason;
    private final String requestId;
    private final String clientIp;
    private final Long policyVersionId;
}
