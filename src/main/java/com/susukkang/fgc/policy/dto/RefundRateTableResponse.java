package com.susukkang.fgc.policy.dto;

import com.susukkang.fgc.common.util.DisplayFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * IF-API-10 예상 해약환급률표 탭 1행 (FGC-FUN-013 / POL-W01)
 */
@Schema(description = "예상 해약환급률표")
public record RefundRateTableResponse(
        @Schema(description = "환급률표 ID", example = "1")
        Long refundRateTableId,
        @Schema(description = "보험회사명")
        String insurerName,
        @Schema(description = "상품명")
        String productName,
        @Schema(description = "납입기간(월)", example = "240")
        Integer paymentTermMonths,
        @Schema(description = "판매채널 코드", example = "FC")
        String channelCode,
        @Schema(description = "평균 공시 환급률(%). 문자열로 정밀도 보존", nullable = true)
        String averageDeclaredRatePct,
        @Schema(description = "표준해약공제액 80% 이상 공제 대상 여부")
        Boolean standardDeduction80Yn,
        @Schema(description = "적용 시작일", example = "2026-01-01")
        LocalDate effectiveFrom,
        @Schema(description = "적용 종료일. 없으면 null", nullable = true)
        LocalDate effectiveTo,
        @Schema(description = "원천 상품 코드(표 버전 식별)", example = "STD-LIFE-A")
        String sourceProductCode,
        @Schema(description = "원천 문서 참조")
        String sourceDocumentRef,
        @Schema(description = "차월별 환급률(1~36차월, 차월 순 정렬)")
        List<RefundRateLineResponse> lines
) {
    public static RefundRateTableResponse from(RefundRateTableRow row) {
        return new RefundRateTableResponse(
                row.getRefundRateTableId(), row.getInsurerName(), row.getProductName(),
                row.getPaymentTermMonths(), row.getChannelCode(),
                DisplayFormat.rate(row.getAverageDeclaredRatePct()),
                row.getStandardDeduction80Yn(), row.getEffectiveFrom(), row.getEffectiveTo(),
                row.getSourceProductCode(), row.getSourceDocumentRef(),
                row.getLines() == null ? List.of()
                        : row.getLines().stream().map(RefundRateLineResponse::from).toList());
    }
}
