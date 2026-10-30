package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.common.code.ContractStatus;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * IF-API-16 계약 상태 변경 이력 응답. registeredBy는 원천에 등록자가 없으면 null이다(현재 사용자로 채우지 않는다).
 */
public record ContractStatusEventResponse(
        int eventSeq,
        ContractStatus previousStatus,
        ContractStatus newStatus,
        OffsetDateTime effectiveAt,
        OffsetDateTime receivedAt,
        List<ContractStatusEventProcessingResponse> processings,
        String sourceSystem,
        String sourceEventKey,
        Long eventId,
        String reasonCode,
        String reason,
        RegisteredBy registeredBy
) {
    public ContractStatusEventResponse {
        processings = List.copyOf(processings);
    }

    public String getPreviousStatusLabel() {
        return previousStatus == null ? null : previousStatus.label();
    }

    public String getNewStatusLabel() {
        return newStatus == null ? null : newStatus.label();
    }

    public record RegisteredBy(Long userId, String loginId, String userName) {
    }
}
