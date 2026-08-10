package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;

/**
 * 설명 : 수수료 지급 건 응답
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public record CommissionPaymentResponse(
        Long paymentId,
        String sourceBusinessKey,
        Integer paymentSequence,
        Long contractId,
        Long agentId,
        String commissionItemCode,
        String commissionItemName,
        BigDecimal amount,
        YearMonth attributionMonth,
        LocalDate scheduledPaymentDate,
        PaymentStage paymentStage,
        CommissionPaymentStatus status,
        Long allocationPolicyVersion,
        List<CommissionPaymentAttributionResponse> attributions,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
