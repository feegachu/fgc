package com.susukkang.fgc.dashboard.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * FGC-UI-DASH-W01 "최근 월 통합검증 실행 3건" 목록 1행.
 * currentStep은 validation_run.current_step(10단계 진행 위치, 0~10) 그대로 노출한다.
 * 진행률(%) = currentStep/10*100 계산은 화면에서 한다.
 */
public record RecentValidationRunRow(
        Long validationRunId,
        LocalDate validationMonth,
        Integer runNo,
        String runType,
        String status,
        Integer currentStep,
        String triggeredBy,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime finalizedAt,
        String finalizedBy,
        String failureMessage
) {
}
