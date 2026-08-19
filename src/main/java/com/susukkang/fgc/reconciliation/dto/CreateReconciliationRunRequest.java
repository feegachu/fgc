package com.susukkang.fgc.reconciliation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 설명 : 대사 실행 생성 요청
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
public record CreateReconciliationRunRequest(
        @NotBlank String settlementMonth,
        @NotBlank String paymentStage,
        @NotNull @Positive Long insurerId,
        @NotNull @Positive Long validationRunId
) {
}
