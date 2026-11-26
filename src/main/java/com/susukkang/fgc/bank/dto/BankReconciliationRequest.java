package com.susukkang.fgc.bank.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** IF-API-98 지급 이체 대사 실행. */
public record BankReconciliationRequest(
        @Schema(description = "대사할 정산월(YYYY-MM). 확정된 달이어야 한다", example = "2026-07") @NotBlank String settlementMonth) {
}
