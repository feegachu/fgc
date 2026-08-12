package com.susukkang.fgc.contract.dto;

import java.time.OffsetDateTime;

/** IF-API-16 계약 상태 사건의 Job별 처리 결과. */
public record ContractStatusEventProcessingResponse(
        String processingJob,
        String processingStatus,
        OffsetDateTime processedAt
) {
}
