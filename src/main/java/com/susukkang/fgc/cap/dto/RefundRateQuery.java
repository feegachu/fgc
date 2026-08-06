package com.susukkang.fgc.cap.dto;

import java.time.LocalDate;

/**
 * ProductRefundRateResolver 조회 조건: 보험사·상품·납입기간·채널·기준일
 * refund_rate_table은 판매버전이 아니라 이 조합(uq_refund_table_scope)으로 하나만 존재
 */
public record RefundRateQuery(
        Long insurerId,
        Long productId,
        Integer paymentTermMonths,
        String channelCode,
        LocalDate asOfDate
) {
}
