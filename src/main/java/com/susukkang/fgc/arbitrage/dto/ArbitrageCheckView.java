package com.susukkang.fgc.arbitrage.dto;

import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.SurrenderValueSourceType;
import lombok.*;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 차익거래 결과 화면용 DTO
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
public class ArbitrageCheckView {
    private Long arbitrageCheckId; // 차익거래 검증 결과 ID
    private Long contractId; // 계약 ID
    private String contractNo; // 화면 표시용 계약번호
    private PaymentStage paymentStage; // 지급 단계
    private LocalDate asOfDate; // 검증 기준일
    private Integer contractMonthNo; // 계약 차월

    private BigDecimal cumulativePaidPremium; // 누적 납입보험료
    private BigDecimal paidCommissionAmount; // 환수·차감을 반영한 확정 수수료 누계
    private BigDecimal plannedCommissionAmount; // 지급예정 수수료 누계
    private BigDecimal includedSurrenderValueAmount; // 계산에 포함된 해약환급금
    private BigDecimal netDifferenceAmount; // 수수료·환급금 합계와 보험료의 차액

    private Boolean refundAdditionAppliedYn; // 환급금 가산 여부
    private SurrenderValueSourceType surrenderValueSourceType; // 환급금 출처

    /** 화면 표기용 한글 라벨. 원본 enum 코드는 감사·연계 식별값으로 함께 유지한다. */
    public String getSurrenderValueSourceTypeLabel() {
        return surrenderValueSourceType == null ? null : surrenderValueSourceType.getLabel();
    }

    private ArbitrageCheckStatus resultStatus; // 판정 결과
    private String decisionReason; // 판정 근거
}
