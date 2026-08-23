package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.contract.code.ContractStatus;

import java.time.OffsetDateTime;

/** contract_status_event 조회용 내부 행. */
public record ContractStatusEventRow(
        Long contractStatusEventId,
        int eventSeq,
        ContractStatus previousStatus,
        ContractStatus newStatus,
        OffsetDateTime effectiveAt,
        OffsetDateTime receivedAt,
        String sourceSystem,
        String sourceEventKey
) {
}
