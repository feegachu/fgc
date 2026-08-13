package com.susukkang.fgc.arbitrage.dto;

import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.SurrenderValueSourceType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : arbitrage_check tb에 INSERT용 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Builder
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ArbitrageCheckInsertDTO {

    private Long arbitrageCheckId; // INSERT 후 생성된 ID
    private Long validationRunId; // 검증 실행 ID
    private Long contractId; // 계약 ID
    private PaymentStage paymentStage; // 지급 단계
    private LocalDate asOfDate; // 검증 기준일
    private Integer contractMonthNo; // 계약 차월

    private BigDecimal cumulativePaidPremium; // 누적 납입보험료
    private BigDecimal paidCommissionAmount; // 기지급 수수료 순액
    private BigDecimal plannedCommissionAmount; // 지급예정 수수료
    private BigDecimal includedSurrenderValueAmount; // 가산 해약환급금
    private Boolean refundAdditionAppliedYn; // 환급금 가산 여부
    private SurrenderValueSourceType surrenderValueSourceType; // 환급금 출처
    private BigDecimal netDifferenceAmount; // 차익거래 계산 차액

    private Long refundRateTableId; // 예상 환급률표 ID
    private Boolean standardDeduction80Yn; // 표준해약공제액 80% 조건
    private ArbitrageCheckStatus resultStatus; // 판정 결과
    private String calculationSnapshot; // 계산 근거 JSON
}