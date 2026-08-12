package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 실시간·배치 한도 검증 결과로 예외 건을 생성하기 위한 명령
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
@Builder
public record CapExceptionCreateCommand(
        Long paymentId,
        Long contractId,
        Long agentId,
        Long policyVersionId,
        Long validationRunId,
        PaymentStage paymentStage,
        LocalDate asOfDate,
        Long capRuleSetId,
        Long refundRateTableId,
        BigDecimal basePremiumAmount,
        BigDecimal refund12mAmount,
        BigDecimal complianceDeductionAmount,
        BigDecimal limitAmount,
        BigDecimal includedAmount,
        BigDecimal remainingAmount,
        BigDecimal usagePct,
        CapResultStatus resultStatus
) {
}
