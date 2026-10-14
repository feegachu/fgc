package com.susukkang.fgc.transaction.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.util.DisplayFormat;
import com.susukkang.fgc.transaction.domain.CapCheckCommand;
import com.susukkang.fgc.transaction.domain.CapRuleSnapshot;

import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : IF-API-24 지급 전 한도 사전검증 미리보기 응답 (FGC-FUN-033)
 *
 * 저장 없이 계산한 결과라 capCheckId 는 항상 null 이다.
 * blockers 문구는 confirm(IF-API-25) 실패 응답과 같은 메시지 카탈로그에서 나온다.
 *
 * @author yslee
 * @since 2026-08-16
 * @version 1.0
 */
public record TransactionPrecheckResponse(
        Long paymentId,
        List<CapPreviewItem> capPreview,
        List<Blocker> blockers,
        boolean confirmable,
        List<CapCandidateExclusion> capExclusions
) {

    public TransactionPrecheckResponse(Long paymentId, List<CapPreviewItem> capPreview,
                                       List<Blocker> blockers, boolean confirmable) {
        this(paymentId, capPreview, blockers, confirmable, List.of());
    }

    /** 귀속 계약 × 지급단계별 1,200% 게이지 1행 (화면 ③ 검증 결과 패널) — 표시 형식은 SIR-008. */
    public record CapPreviewItem(
            Long contractId,
            String contractNo,
            PaymentStage paymentStage,
            String paymentStageLabel,
            LocalDate asOfDate,
            long basePremiumAmount,
            @Schema(types = {"integer", "null"})
            Long refund12mAmount,
            @Schema(types = {"integer", "null"})
            Long complianceDeductionAmount,
            @Schema(types = {"integer", "null"})
            Long limitAmount,
            @Schema(types = {"integer", "null"})
            Long existingIncludedAmount,
            @Schema(types = {"integer", "null"})
            Long candidateAmount,
            @Schema(types = {"integer", "null"})
            Long includedAmount,
            @Schema(types = {"integer", "null"})
            Long remainingAmount,
            @Schema(types = {"string", "null"})
            String usagePct,
            CapResultStatus resultStatus,
            String resultStatusLabel,
            @Schema(types = {"integer", "null"})
            Long capRuleSetId,
            Long capCheckId,
            @Schema(types = {"string", "null"})
            String applicabilityReasonCode,
            @Schema(types = {"string", "null"})
            String applicabilityReason
    ) {
        /** 확정 경로와 같은 buildCapCheck 결과를 저장하지 않고 그대로 표시 형식으로 변환한다. */
        public static CapPreviewItem from(
                CapCheckCommand check,
                CapRuleSnapshot rule,
                String contractNo
        ) {
            PaymentStage stage = PaymentStage.valueOf(check.getPaymentStage());
            CapResultStatus status = check.getResultStatus();
            return new CapPreviewItem(
                    check.getContractId(),
                    contractNo,
                    stage,
                    stage.label(),
                    check.getAsOfDate(),
                    DisplayFormat.nullableWon(check.getBasePremiumAmount()),
                    DisplayFormat.nullableWon(check.getRefund12mAmount()),
                    DisplayFormat.nullableWon(check.getComplianceDeductionAmount()),
                    DisplayFormat.nullableWon(check.getLimitAmount()),
                    rule == null ? null : DisplayFormat.nullableWon(rule.existingIncludedAmount()),
                    DisplayFormat.nullableWon(check.getCandidateAmount()),
                    DisplayFormat.nullableWon(check.getIncludedAmount()),
                    DisplayFormat.nullableWon(check.getRemainingAmount()),
                    DisplayFormat.rate(check.getUsagePct()),
                    status,
                    status.label(),
                    check.getCapRuleSetId(),
                    null,
                    status == CapResultStatus.NOT_APPLICABLE ? check.getApplicabilityReasonCode() : null,
                    status == CapResultStatus.NOT_APPLICABLE ? check.getDecisionReason() : null
            );
        }
    }

    /** 현재 상태로 확정하면 실패할 사유 1건 — code 는 3장 오류 카탈로그, message 는 부록 A 표준 문구. */
    public record Blocker(
            String code,
            String message,
            Long contractId,
            Long transactionAttributionId
    ) {
    }
}
