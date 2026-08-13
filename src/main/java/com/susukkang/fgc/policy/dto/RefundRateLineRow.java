package com.susukkang.fgc.policy.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * POL-W01 예상 해약환급률표 탭의 차월별 환급률 1행. refund_rate_line 투영 (1~36차월)
 */
@Getter
@Setter
public class RefundRateLineRow {
    private Integer contractMonthNo;
    private BigDecimal refundRatePct;
}
