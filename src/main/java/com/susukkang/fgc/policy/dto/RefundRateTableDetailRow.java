package com.susukkang.fgc.policy.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 환급률표와 차월별 환급률을 함께 조회한 한 행. 라인이 없는 표는 라인 필드가 null이다.
 */
public record RefundRateTableDetailRow(
        Long refundRateTableId,
        String insurerName,
        String productName,
        Integer paymentTermMonths,
        String channelCode,
        BigDecimal averageDeclaredRatePct,
        Boolean standardDeduction80Yn,
        LocalDate effectiveFrom,
        LocalDate effectiveTo,
        String sourceProductCode,
        String sourceDocumentRef,
        Integer contractMonthNo,
        BigDecimal refundRatePct
) {
}
