package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 설명 : 수수료 지급 건 계약별 귀속 요청
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
public record CommissionPaymentAttributionRequest(
        Long contractId,
        @NotNull @DecimalMin("0.00") @Digits(integer = 13, fraction = 2) BigDecimal amount,
        @NotNull InclusionDecisionStatus inclusionDecisionStatus,
        ExclusionType exclusionType,
        @NotBlank @Size(max = 1000) String inclusionDecisionReason,
        @Size(max = 200) String allocationBasis,
        @Size(max = 500) String evidenceRef,
        @NotNull AttributionMethod attributionMethod
) {
}
