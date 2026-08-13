package com.susukkang.fgc.contract.dto;

import java.time.OffsetDateTime;

/** contract_status_event_processing 조회용 내부 행. */
public record ContractStatusEventProcessingRow(
        Long contractStatusEventId,
        String processingJob,
        String processingStatus,
        OffsetDateTime processedAt
) {
    public ContractStatusEventProcessingResponse toResponse() {
        return new ContractStatusEventProcessingResponse(processingJob, processingStatus, processedAt);
    }
}
