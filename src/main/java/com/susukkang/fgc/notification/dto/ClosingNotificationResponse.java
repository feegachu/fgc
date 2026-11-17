package com.susukkang.fgc.notification.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : IF-API-93 마감 알림 발송 이력 1행. 수신번호는 가운데를 가린 값만 내보내고,
 * 수신 동의를 철회한 사람은 번호가 없다. 수신 대상이 없던 사건은 수신자 항목이 모두 null 이다.
 *
 * @author yslee
 * @version 1.0
 * @since 2026-11-17
 */
public record ClosingNotificationResponse(
        Long closingNotificationId,
        String eventType,
        Long validationRunId,
        LocalDate validationMonth,
        @Schema(nullable = true, description = "수신 대상이 없던 사건이면 null") String recipientName,
        @Schema(nullable = true, description = "수신 대상이 없던 사건이면 null") String recipientLoginId,
        @Schema(nullable = true, description = "가운데를 가린 번호. 수신 대상이 없거나 수신을 해제했으면 null") String recipientPhone,
        String messageText,
        String status,
        int attemptCount,
        @Schema(nullable = true, description = "실패하거나 건너뛴 사유. 없으면 null") String errorCode,
        OffsetDateTime createdAt,
        @Schema(nullable = true, description = "보내지 않았으면 null") OffsetDateTime sentAt) {
}
