package com.susukkang.fgc.base.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "수수료 항목 기준정보")
public record CommissionItemResponse(
        @Schema(description = "수수료 항목 코드", example = "BASE_COMMISSION")
        String itemCode,
        @Schema(description = "수수료 항목명", example = "FC 기본수수료")
        String itemName,
        @Schema(description = "현금흐름 유형", allowableValues = {"PAYMENT", "DEDUCTION"})
        String cashflowType,
        @Schema(description = "수수료 항목 카테고리", example = "SALES")
        String itemCategory,
        @Schema(description = "사용 시작일", example = "2026-01-01")
        LocalDate effectiveFrom,
        @Schema(description = "사용 종료일. 종료일이 없으면 null", example = "2026-12-31", nullable = true)
        LocalDate effectiveTo
) {
}
