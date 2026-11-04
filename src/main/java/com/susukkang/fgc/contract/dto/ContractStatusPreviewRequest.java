package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.common.code.ContractStatus;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

/** IF-API-64 요청. 사건 종류는 바꿀 계약 상태이고 효력일시는 IF-API-19 statusEvent.effectiveAt 과 같다. */
public record ContractStatusPreviewRequest(
        @NotNull ContractStatus eventType,
        @NotNull OffsetDateTime effectiveAt
) {
}
