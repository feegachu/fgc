package com.susukkang.fgc.base.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

@Schema(description = "보험상품 판매버전 기준정보")
public record ProductResponse(
        @Schema(description = "상품 판매버전 ID", example = "21")
        Long productOfferingId,
        @Schema(description = "보험회사 상품코드", example = "P-B-001")
        String insurerProductCode,
        @Schema(description = "FGC 표준 상품코드", example = "STD-LIFE-B")
        String standardProductCode,
        @Schema(description = "상품명", example = "가상 저해지 건강보험 B")
        String productName,
        @Schema(description = "상품군 코드", example = "HEALTH_PROTECTION")
        String productGroupCode,
        @Schema(description = "판매버전", example = "2026-CURRENT-B")
        String offeringVersion,
        @Schema(description = "판매 시작일", example = "2026-07-01")
        LocalDate salesStartDate,
        @Schema(description = "판매 종료일. 종료일이 없으면 null", example = "2026-12-31", nullable = true)
        LocalDate salesEndDate,
        @Schema(description = "기초서류 버전", example = "BD-2026-07")
        String basicDocumentVersion,
        @Schema(description = "기초서류 기준일", example = "2026-07-01")
        LocalDate basicDocumentDate,
        @Schema(description = "판매 채널 코드", example = "FACE_TO_FACE")
        String channelCode,
        @Schema(description = "채널 특례 적용 여부", example = "false")
        boolean channelSpecialRuleYn,
        @Schema(description = "수수료 체계 코드", example = "CURRENT")
        String feeRegimeCode,
        @Schema(description = "표준해약공제액 80% 이상 공제 상품 여부", example = "true")
        boolean standardDeduction80Yn,
        @Schema(description = "활성 해약환급률표의 납입기간(개월). 표가 없으면 null", example = "240", nullable = true)
        Integer paymentTermMonths
) {
    public static ProductResponse from(ProductRow row) {
        return new ProductResponse(
                row.productOfferingId(),
                row.insurerProductCode(),
                row.standardProductCode(),
                row.productName(),
                row.productGroupCode(),
                row.offeringVersion(),
                row.salesStartDate(),
                row.salesEndDate(),
                row.basicDocumentVersion(),
                row.basicDocumentDate(),
                row.channelCode(),
                row.channelSpecialRuleYn(),
                row.feeRegimeCode(),
                row.standardDeduction80Yn(),
                row.paymentTermMonths()
        );
    }
}
