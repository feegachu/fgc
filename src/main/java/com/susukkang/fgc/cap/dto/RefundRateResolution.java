package com.susukkang.fgc.cap.dto;

import java.math.BigDecimal;

/**
 * ProductRefundRateResolver 의 조회 결과
 * 12차월 예상 해약환급률(%)과, 판정에 사용한 표(refundRateTableId)·정책버전을 함께 돌려줌
 * 한도 판정(cap_check)에는 반드시 refundRateTableId 를 저장해 "그때 어느 버전을 썼는지"를 남김
 */
public record RefundRateResolution(
        Long refundRateTableId,
        Long policyVersionId,
        Integer versionNo,
        BigDecimal month12RatePct
) {
}
