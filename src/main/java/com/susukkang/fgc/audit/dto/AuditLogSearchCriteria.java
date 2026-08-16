package com.susukkang.fgc.audit.dto;

import java.time.LocalDateTime;

/**
 * FUN-061 IF-API-52 감사로그 검색조건. 서비스가 정규화(blank→null, to→exclusive 상한)한 값만 담는다.
 */
public record AuditLogSearchCriteria(
        String entityType,
        String entityId,
        Long userId,
        String action,
        LocalDateTime from,
        LocalDateTime toExclusive
) {
}
