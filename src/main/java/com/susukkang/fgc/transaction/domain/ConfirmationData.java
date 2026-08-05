package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;
import java.time.LocalDate;

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
        Long policyVersionId,
        InclusionDecisionStatus inclusionDecisionStatus,
        String inclusionDecisionReason,
        String allocationBasis,
        String evidenceRef
) {
}
