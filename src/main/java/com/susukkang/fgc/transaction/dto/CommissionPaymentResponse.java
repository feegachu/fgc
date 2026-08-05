package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;

public record CommissionPaymentResponse(
        Long paymentId,
        String sourceBusinessKey,
        Long contractId,
        Long agentId,
        String commissionItemCode,
        String commissionItemName,
        BigDecimal amount,
        YearMonth attributionMonth,
        LocalDate scheduledPaymentDate,
        PaymentStage paymentStage,
        CommissionPaymentStatus status,
        Long attributedContractId,
        InclusionDecisionStatus inclusionDecisionStatus,
        String inclusionDecisionReason,
        Long allocationPolicyVersion,
        String allocationBasis,
        String evidenceRef,
        AttributionMethod attributionMethod,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
