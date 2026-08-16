package com.susukkang.fgc.audit.dto;

import java.time.OffsetDateTime;

/**
 * 감사로그 SQL 결과를 서비스 계층으로 전달하는 내부 프로젝션.
 * userLoginId 는 app_user LEFT JOIN 결과라 배치 발 감사행(user_id NULL)에서는 null 이다.
 */
public record AuditLogRow(
        Long auditLogId,
        OffsetDateTime occurredAt,
        Long userId,
        String userLoginId,
        String actionCode,
        String entityType,
        String entityId,
        String beforeValue,
        String afterValue,
        String reason,
        String requestId,
        Long policyVersionId
) {
}
