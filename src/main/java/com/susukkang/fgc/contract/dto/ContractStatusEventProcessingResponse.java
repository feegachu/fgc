package com.susukkang.fgc.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.OffsetDateTime;

/**
 * IF-API-16 계약 상태 사건의 Job별 처리 결과. processingJob·processingStatus는 기존 응답 호환용으로 job·status와 같은 값이다.
 * summary는 후속 작업의 성공 행에만, errorCode는 후속 작업의 실패 행에만 있다. 실패·배치 행의 null을 0건으로 읽지 않는다.
 */
public record ContractStatusEventProcessingResponse(
        String processingJob,
        String processingStatus,
        OffsetDateTime processedAt,
        String job,
        String status,
        @Schema(nullable = true) ProcessingSummary summary,
        @Schema(nullable = true) String errorCode
) {
    /** 그 작업이 남긴 영향 건수(contract_status_event_effect). */
    public record ProcessingSummary(int heldPayments, int resumedPayments, int changedScheduleLines, int excludedCount) {
    }
}
