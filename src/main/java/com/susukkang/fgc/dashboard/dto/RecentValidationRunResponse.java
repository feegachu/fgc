package com.susukkang.fgc.dashboard.dto;

import com.susukkang.fgc.common.code.ValidationRunStatus;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * FGC-UI-DASH-W01 "최근 월 통합검증 실행" 응답 1행 (IF-API-03).
 * SIR-008 — 코드값은 영문 코드와 한글 라벨을 함께 내려준다. {@link RecentValidationRunRow}(Mapper 원본)를
 * 그대로 노출하지 않고 이 응답 DTO로 감싸 서버가 라벨을 만들어 붙인다.
 */
public record RecentValidationRunResponse(
        Long validationRunId,
        LocalDate validationMonth,
        Integer runNo,
        String runType,
        String status,
        String statusLabel,
        Integer currentStep,
        String triggeredBy,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime finalizedAt,
        String finalizedBy,
        String failureMessage
) {
    public static RecentValidationRunResponse from(RecentValidationRunRow row) {
        return new RecentValidationRunResponse(
                row.validationRunId(),
                row.validationMonth(),
                row.runNo(),
                row.runType(),
                row.status(),
                ValidationRunStatus.valueOf(row.status()).label(),
                row.currentStep(),
                row.triggeredBy(),
                row.startedAt(),
                row.completedAt(),
                row.finalizedAt(),
                row.finalizedBy(),
                row.failureMessage()
        );
    }
}
