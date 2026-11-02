package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.common.code.ContractStatusReasonCode;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.OffsetDateTime;

/** IF-API-19 상태 변경 사건. 일자 범위는 서버가 잠근 계약 기준으로 검사한다(운영정책서 제13조의2 3항). */
public record ContractStatusEventRequest(
        @NotNull OffsetDateTime effectiveAt,
        @NotNull OffsetDateTime receivedAt,
        // @Size의 min=1은 OpenAPI minLength용이다 — @NotBlank는 스펙에 최소 길이를 내지 않아 max만 두면 0이 된다.
        @NotBlank @Size(min = 1, max = 40) @Schema(implementation = ContractStatusReasonCode.class) String reasonCode,
        @NotBlank @Size(min = 1, max = 2000) String reason
) {
}
