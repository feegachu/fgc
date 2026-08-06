package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 지급 확정 및 한도 검증에 필요한 잠금 조회 데이터
 *
 * @author yslee
 * @since 2026-08-06
 * @version 1.2
 */
public record ConfirmationData(
        Long paymentId,
        CommissionPaymentStatus status,
        BigDecimal amount,
        BigDecimal attributedAmount,
        LocalDate attributionMonth,
        Long transactionAttributionId,
        Long contractId,
        Long agentId,
        PaymentStage paymentStage,
        Long commissionItemId,
        String itemCode,
        String itemName,
        Long policyVersionId,
        InclusionDecisionStatus inclusionDecisionStatus,
        String inclusionDecisionReason,
        String allocationBasis,
        String evidenceRef
) {
}
