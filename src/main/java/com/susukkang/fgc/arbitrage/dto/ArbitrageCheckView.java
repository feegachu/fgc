package com.susukkang.fgc.arbitrage.dto;

import com.susukkang.fgc.common.code.ArbitrageCheckStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.SurrenderValueSourceType;
import lombok.*;
import io.swagger.v3.oas.annotations.media.Schema;

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

    /** SIR-008 화면 표기용 한글 라벨. 원본 enum 코드는 감사·연계 식별값으로 함께 유지한다. */
    public String getPaymentStageLabel() {
        return paymentStage == null ? null : paymentStage.label();
    }

    private LocalDate asOfDate; // 검증 기준일
    private Integer contractMonthNo; // 계약 차월

    private BigDecimal cumulativePaidPremium; // 누적 납입보험료
    private BigDecimal paidCommissionAmount; // 환수·차감을 반영한 확정 수수료 누계
    private BigDecimal plannedCommissionAmount; // snapshot 계산 기준에 따른 예정 참고액
    /** 저장 당시 계산 기준. 과거 미기록 결과는 null이며 현재 기준으로 추정하지 않는다. */
    @Schema(nullable = true, description = "저장 당시 예정액 계산 기준. REMAINING_V1은 잔여액, UNRESOLVED_UPPER_BOUND_V1은 미차감 상한이며 과거 미기록은 null이다.")
    private String plannedCommissionBasis;
    private BigDecimal includedSurrenderValueAmount; // 계산에 포함된 해약환급금
    private BigDecimal netDifferenceAmount; // 수수료·환급금 합계와 보험료의 차액

    @Schema(nullable = true, description = "예정 포함 사전 모니터링 초과액. 연결 불명확 등으로 계산할 수 없으면 null이며 지급분 판정에는 사용하지 않는다.")
    private BigDecimal projectedExcessAmount; // 지급예정 포함 초과액: 사전 모니터링 전용
    private String candidateType; // CURRENT_EXCESS / PROJECTED_EXCESS / NONE

    private Boolean refundAdditionAppliedYn; // 환급금 가산 여부
    private SurrenderValueSourceType surrenderValueSourceType; // 환급금 출처

    /** 화면 표기용 한글 라벨. 원본 enum 코드는 감사·연계 식별값으로 함께 유지한다. */
    public String getSurrenderValueSourceTypeLabel() {
        return surrenderValueSourceType == null ? null : surrenderValueSourceType.getLabel();
    }

    private ArbitrageCheckStatus resultStatus; // 판정 결과

    /** SIR-008 화면 표기용 한글 라벨. 원본 enum 코드는 감사·연계 식별값으로 함께 유지한다. */
    public String getResultStatusLabel() {
        return resultStatus == null ? null : resultStatus.label();
    }

    private String decisionReason; // 판정 근거
}
