package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.transaction.dto.CommissionPaymentResponse;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 설명 : 수수료 지급 건 조회 데이터
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public record CommissionPaymentRow(
        Long paymentId,
        String sourceType,
        String sourceBusinessKey,
        Integer paymentSequence,
        Long contractId,
        Long agentId,
        Long commissionItemId,
        String commissionItemCode,
        String commissionItemName,
        BigDecimal amount,
        LocalDate settlementMonth,
        String cashflowType,
        LocalDate scheduledPaymentDate,
        PaymentStage paymentStage,
        CommissionPaymentStatus status,
        Long allocationPolicyVersion,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    public CommissionPaymentResponse toResponse(
            List<CommissionPaymentAttributionRow> attributions,
            List<Long> capCheckIds
    ) {
        return new CommissionPaymentResponse(
                paymentId,
                sourceType,
                sourceBusinessKey,
                paymentSequence,
                contractId,
                agentId,
                commissionItemId,
                commissionItemCode,
                commissionItemName,
                amount,
                settlementMonth,
                cashflowType,
                scheduledPaymentDate,
                paymentStage,
                status,
                allocationPolicyVersion,
                attributions.stream().map(CommissionPaymentAttributionRow::toResponse).toList(),
                List.copyOf(capCheckIds),
                null,
                note,
                createdAt,
                updatedAt
        );
    }
}
