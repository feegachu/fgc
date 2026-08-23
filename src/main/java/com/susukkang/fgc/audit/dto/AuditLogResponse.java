package com.susukkang.fgc.audit.dto;

import com.susukkang.fgc.common.util.DateUtil;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * FUN-061 IF-API-52 감사로그 조회 응답.
 * 인터페이스정의서 응답 필드에 화면(AUDT-W01) 목록 컬럼인 entityId·policyVersionId 를 더한다.
 * userLoginId 가 null 이면 배치 발 감사행이며 "BATCH" 표기는 화면 책임이다.
 */
@Schema(description = "감사로그 조회 응답")
public record AuditLogResponse(
        @Schema(description = "감사로그 ID", example = "1")
        Long auditLogId,
        @Schema(description = "발생시각")
        OffsetDateTime occurredAt,
        @Schema(description = "행위자 사용자 ID (배치는 null)", example = "12")
        Long userId,
        @Schema(description = "행위자 로그인 ID (배치는 null)", example = "settle01")
        String userLoginId,
        @Schema(description = "행위 종류", example = "PAYMENT_CONFIRMED")
        String actionCode,
        @Schema(description = "대상 종류", example = "COMMISSION_PAYMENT")
        String entityType,
        @Schema(description = "대상 ID", example = "42")
        String entityId,
        @Schema(description = "변경 전 값(JSON)")
        String beforeValue,
        @Schema(description = "변경 후 값(JSON)")
        String afterValue,
        @Schema(description = "사유")
        String reason,
        @Schema(description = "요청추적 ID", example = "20260816-1a2b3c")
        String requestId,
        @Schema(description = "정책버전 ID", example = "3")
        Long policyVersionId
) {

    public static AuditLogResponse from(AuditLogRow row) {
        return new AuditLogResponse(
                row.auditLogId(),
                DateUtil.toSeoul(row.occurredAt()),
                row.userId(),
                row.userLoginId(),
                row.actionCode(),
                row.entityType(),
                row.entityId(),
                row.beforeValue(),
                row.afterValue(),
                row.reason(),
                row.requestId(),
                row.policyVersionId()
        );
    }
}
