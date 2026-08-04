package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 1,200% 한도 판정 API 응답.
 * resultStatus 가 REVIEW_REQUIRED 여도 이건 에러가 아니라 유효한 계산 결과다 — 사람이 봐야
 * 한다는 판정일 뿐이라 HTTP 상태는 항상 200이고, 클라이언트는 이 필드로 구분한다.
 */
public record CapCheckResponse(
        Long capCheckId,
        Long contractId,
        PaymentStage paymentStage,
        CapCheckKind checkKind,
        LocalDate asOfDate,
        Long capRuleSetId,
        Long refundRateTableId,
        BigDecimal basePremiumAmount,
        BigDecimal refund12mAmount,
        BigDecimal complianceDeductionAmount,
        BigDecimal limitAmount,
        BigDecimal includedAmount,
        BigDecimal remainingAmount,
        BigDecimal usagePct,
        CapResultStatus resultStatus,
        List<CapCheckDetailResponse> details
) {
    public static CapCheckResponse from(CapCheckSaveResult saved) {
        CapCalculationResult r = saved.result();
        List<CapCheckDetailResponse> details = r.details().stream()
                .map(CapCheckDetailResponse::from)
                .toList();
        return new CapCheckResponse(
                saved.capCheckId(), r.contractId(), r.paymentStage(), r.checkKind(), r.asOfDate(),
                r.capRuleSetId(), r.refundRateTableId(), r.basePremiumAmount(), r.refund12mAmount(),
                r.complianceDeductionAmount(), r.limitAmount(), r.includedAmount(), r.remainingAmount(),
                r.usagePct(), r.resultStatus(), details);
    }
}
