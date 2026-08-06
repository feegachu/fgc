package com.susukkang.fgc.audit.dto;

import lombok.Builder;
import lombok.Getter;

// FUN-001 개발 순서 8

/**
 * audit_log INSERT 파라미터. audit_log는 append-only 다(trg_audit_log_append_only).
 *
 * entityType/entityId/actionCode 는 NOT NULL 이다. userId·clientIp·requestId·reason 은 null 허용.
 */
@Getter
@Builder
public class AuditLogInsertRow {
    private final Long userId;
    private final String actionCode;
    private final String entityType;
    private final String entityId;
    private final String reason;
    private final String requestId;
    private final String clientIp;
}
