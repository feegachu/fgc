package com.susukkang.fgc.dashboard.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * FGC-UI-DASH-W01 "최근 월 통합검증 실행 3건" 목록 1행
 * 10단계 진행률(%) X 수정 전 까지 status 5단계만 노출
 */
public record RecentValidationRunRow(
        Long validationRunId,
        LocalDate validationMonth,
        Integer runNo,
        String runType,
        String status,
        String triggeredBy,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime finalizedAt,
        String finalizedBy,
        String failureMessage
) {
}
