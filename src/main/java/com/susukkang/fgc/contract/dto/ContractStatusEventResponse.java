package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.contract.domain.ContractStatus;

import java.time.OffsetDateTime;
import java.util.List;

/** IF-API-16 계약 상태 변경 이력 응답. */
public record ContractStatusEventResponse(
        int eventSeq,
        ContractStatus previousStatus,
        ContractStatus newStatus,
        OffsetDateTime effectiveAt,
        OffsetDateTime receivedAt,
        List<ContractStatusEventProcessingResponse> processings,
        String sourceSystem,
        String sourceEventKey
) {
    public ContractStatusEventResponse {
        processings = List.copyOf(processings);
    }
}
