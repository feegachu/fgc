package com.susukkang.fgc.arbitrage.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 계약 단건 차익거래 검증에 필요한 계약 및 최신 금융 스냅샷을 전달한다.
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Getter
@Setter
public class ArbitrageCalculationSource {
    private Long contractId; // 계약 ID
    private LocalDate contractDate; // 계약일
    private Long insurerId; // 보험사 ID
    private Long productId; // 상품 ID
    private Long productOfferingId; // 상품 판매 버전 ID
    private Integer paymentTermMonths; // 납입 기간(개월)
    private String channelCode; // 판매 채널
    private Boolean standardDeduction80Yn; // 표준해약공제액 80% 적용 여부
    private BigDecimal standardSurrenderDeductionAmount; // 표준해약공제액
    private LocalDate snapshotAsOfDate; // 적용 금융 스냅샷 기준일
    private Integer contractMonthNo; // 계약 차월
    private BigDecimal cumulativePaidPremium; // 누적 납입보험료
    private BigDecimal surrenderValue; // 실제 해약환급금
    private String surrenderValueType; // 금융 스냅샷 환급금 유형
    private Long snapshotRefundRateTableId; // 금융 스냅샷의 환급률표 ID
}
