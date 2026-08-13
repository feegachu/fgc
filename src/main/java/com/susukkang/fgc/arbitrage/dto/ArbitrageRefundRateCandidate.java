package com.susukkang.fgc.arbitrage.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * 설명 : 차익거래 예상 해약환급금 계산에 사용할 환급률표 후보를 전달한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Getter
@Setter
public class ArbitrageRefundRateCandidate {
    private Long refundRateTableId; // 환급률표 ID
    private BigDecimal refundRatePct; // 계약 차월 예상 환급률
}
