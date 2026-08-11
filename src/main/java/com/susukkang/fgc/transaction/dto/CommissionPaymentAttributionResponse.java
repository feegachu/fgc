package com.susukkang.fgc.transaction.dto;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 수수료 지급 건 계약별 귀속 응답
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
public record CommissionPaymentAttributionResponse(
        Integer attributionSequence,
        Long contractId,
        LocalDate attributionDate,
        LocalDate attributionMonth,
        BigDecimal amount,
        InclusionDecisionStatus inclusionDecisionStatus,
        ExclusionType exclusionType,
        String inclusionDecisionReason,
        String allocationBasis,
        String evidenceRef,
        AttributionMethod attributionMethod
) {
}
