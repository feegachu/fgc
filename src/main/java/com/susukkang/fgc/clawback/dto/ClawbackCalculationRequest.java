package com.susukkang.fgc.clawback.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** IF-API-85 계산 요청. 금액·환수율은 받지 않고 서버가 잠근 원천·정책으로만 계산한다. */
public record ClawbackCalculationRequest(
        @Schema(description = "IF-API-84에서 받은 sourceVersion. 다르면 409 FGC-IMPL-STATE_CHANGED")
        @NotBlank @Size(max = 128) String expectedSourceVersion,
        @Schema(description = "IF-API-84 policyCandidates 중 사용자가 확인한 정책버전")
        @NotNull @Positive Long policyVersionId,
        @Schema(description = "계산 사유 1~500자")
        @NotBlank @Size(max = 500) String reason
) { }
