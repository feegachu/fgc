package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;

/**
 * 설명 : 수수료 지급 건 조회 데이터
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public record CommissionPaymentRow(
        Long paymentId,
        String sourceBusinessKey,
        Long contractId,
        Long agentId,
        String commissionItemCode,
        String commissionItemName,
        BigDecimal amount,
        LocalDate attributionMonth,
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
    public CommissionPaymentResponse toResponse() {
        return new CommissionPaymentResponse(
                paymentId,
                sourceBusinessKey,
                contractId,
                agentId,
                commissionItemCode,
                commissionItemName,
                amount,
                YearMonth.from(attributionMonth),
                scheduledPaymentDate,
                paymentStage,
                status,
                attributedContractId,
                inclusionDecisionStatus,
                inclusionDecisionReason,
                allocationPolicyVersion,
                allocationBasis,
                evidenceRef,
                attributionMethod,
                note,
                createdAt,
                updatedAt
        );
    }
}
