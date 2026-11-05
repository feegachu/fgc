package com.susukkang.fgc.contract.dto;

import java.time.OffsetDateTime;

/** contract_status_event_processing 조회용 내부 행. failureReason은 내부 정보가 섞일 수 있어 응답에 그대로 내보내지 않는다. */
public record ContractStatusEventProcessingRow(
        Long contractStatusEventId,
        String processingJob,
        String processingStatus,
        OffsetDateTime processedAt,
        String failureReason
) {
}
