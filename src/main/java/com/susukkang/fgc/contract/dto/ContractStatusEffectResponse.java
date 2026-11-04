package com.susukkang.fgc.contract.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;

/** IF-API-89 영향 1건. 처리한 대상과 제외한 대상(nextState=EXCLUDED)을 같은 형태로 돌려준다. 금액이 없으면 null 이다(0원과 다르다). */
public record ContractStatusEffectResponse(
        String entityType,
        Long entityId,
        String processingJob,
        @Schema(nullable = true) String previousState,
        String nextState,
        @Schema(nullable = true) BigDecimal amount,
        @Schema(nullable = true) Long policyVersionId,
        String reasonCode,
        @Schema(nullable = true) String reason) {
}
