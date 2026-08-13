package com.susukkang.fgc.policy.dto;

import com.susukkang.fgc.common.util.DisplayFormat;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * IF-API-10 예상 해약환급률표 차월별 환급률 1행 (FGC-FUN-013 / POL-W01)
 */
@Schema(description = "차월별 예상 해약환급률")
public record RefundRateLineResponse(
        @Schema(description = "계약 차월(1~36)", example = "12")
        Integer contractMonthNo,
        @Schema(description = "예상 해약환급률(%). 문자열로 정밀도 보존", example = "34.800000")
        String refundRatePct
) {
    public static RefundRateLineResponse from(RefundRateLineRow row) {
        return new RefundRateLineResponse(row.getContractMonthNo(), DisplayFormat.rate(row.getRefundRatePct()));
    }
}
