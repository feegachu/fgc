package com.susukkang.fgc.audit.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 설명 : FUN-061 감사 검색조건. 서비스가 정규화한 값과 기존 시간대 해석에 맞춘 JPQL 바인딩 값을 제공한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-09-26
 */
public record AuditLogSearchCriteria(
        String entityType,
        String entityId,
        Long userId,
        String action,
        LocalDateTime from,
        LocalDateTime toExclusive
) {

    /**
     * 설명 : 조회 시작 시각을 기존 JDBC 바인딩과 동일한 JVM 기본 시간대의 OffsetDateTime으로 변환한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    public OffsetDateTime fromTimestamp() {
        return from == null ? null : from.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }

    /**
     * 설명 : 종료일 다음 날의 제외 상한을 기존과 동일한 JVM 기본 시간대로 변환한다.
     *
     * @author hjKang
     * @version 1.0
     * @since 2026-09-26
     */
    public OffsetDateTime toExclusiveTimestamp() {
        return toExclusive == null ? null : toExclusive.atZone(ZoneId.systemDefault()).toOffsetDateTime();
    }
}
