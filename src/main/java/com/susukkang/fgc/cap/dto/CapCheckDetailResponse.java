package com.susukkang.fgc.cap.dto;

import java.math.BigDecimal;

/** cap_check_detail 1건의 API 응답 표현. */
public record CapCheckDetailResponse(
        int detailSeq,
        Long commissionItemId,
        String itemCode,
        Long scheduleLineId,
        int contractMonthNo,
        String classification,
        BigDecimal amount,
        String decisionReason
) {
    public static CapCheckDetailResponse from(CapCheckDetailLine line) {
        return new CapCheckDetailResponse(
                line.detailSeq(), line.commissionItemId(), line.itemCode(), line.scheduleLineId(),
                line.contractMonthNo(), line.classification(), line.amount(), line.decisionReason());
    }
}
