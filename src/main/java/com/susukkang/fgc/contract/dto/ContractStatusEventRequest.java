package com.susukkang.fgc.contract.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** IF-API-19 상태 변경 사건. 일자 범위는 서버가 잠근 계약 기준으로 검사한다(운영정책서 제13조의2 3항). */
public record ContractStatusEventRequest(
        @NotNull OffsetDateTime effectiveAt,
        @NotNull OffsetDateTime receivedAt,
        @NotBlank @Size(max = 40) String reasonCode,
        @NotBlank @Size(max = 2000) String reason
) {
}
