package com.susukkang.fgc.transaction.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.susukkang.fgc.common.code.CommissionPaymentStatus;
import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 설명 : 수수료 지급 건 응답
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public record CommissionPaymentResponse(
        @JsonProperty("commissionTransactionId") Long paymentId,
        String sourceType,
        String sourceBusinessKey,
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
        List<CommissionPaymentAttributionResponse> attributions,
        List<Long> capCheckIds,
        Long journalHeaderId,
        String note,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
}
