package com.susukkang.fgc.transaction.domain;

import com.susukkang.fgc.common.code.AttributionMethod;
import com.susukkang.fgc.common.code.ExclusionType;
import com.susukkang.fgc.common.code.InclusionDecisionStatus;
import com.susukkang.fgc.transaction.dto.CommissionPaymentAttributionResponse;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 수수료 지급 건 계약별 귀속 조회 데이터
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
public record CommissionPaymentAttributionRow(
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
    public CommissionPaymentAttributionResponse toResponse() {
        return new CommissionPaymentAttributionResponse(
                attributionSequence,
                contractId,
                attributionDate,
                attributionMonth,
                amount,
                inclusionDecisionStatus,
                exclusionType,
                inclusionDecisionReason,
                allocationBasis,
                evidenceRef,
                attributionMethod
        );
    }
}
