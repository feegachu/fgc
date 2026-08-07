package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;

/**
 * 설명 : 수수료 지급 건 수정 요청
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public record CommissionPaymentUpdateRequest(
        @NotNull @Positive Integer paymentSequence,
        Long contractId,
        @NotNull Long agentId,
        @NotBlank @Size(max = 50) String commissionItemCode,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull YearMonth attributionMonth,
        @NotNull LocalDate scheduledPaymentDate,
        @NotNull PaymentStage paymentStage,
        Long attributedContractId,
        @NotNull InclusionDecisionStatus inclusionDecisionStatus,
        @NotBlank @Size(max = 1000) String inclusionDecisionReason,
        Long allocationPolicyVersion,
        @Size(max = 200) String allocationBasis,
        @Size(max = 500) String evidenceRef,
        @NotNull AttributionMethod attributionMethod,
        @Size(max = 1000) String note
) {
}
