package com.susukkang.fgc.cap.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import com.susukkang.fgc.common.code.CapResultStatus;
import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.util.DisplayFormat;

import java.time.LocalDate;

/**
 * cap_check 1건의 목록/게이지용 API 응답
 */
public record CapCheckItemResponse(
        Long capCheckId,
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
        Long includedAmount,
        @Schema(types = {"integer", "null"})
        Long remainingAmount,
        @Schema(types = {"string", "null"})
        String usagePct,
        CapResultStatus resultStatus,
        String resultStatusLabel,
        @Schema(types = {"integer", "null"})
        Long capRuleSetId
) {
    /** IF-API-30 목록 1행 — insurance_contract 조인으로 contractNo 를 이미 갖고 있다. */
    public static CapCheckItemResponse from(CapCheckListRow row) {
        PaymentStage stage = PaymentStage.valueOf(row.getPaymentStage());
        CapResultStatus status = CapResultStatus.valueOf(row.getResultStatus());
        return new CapCheckItemResponse(
                row.getCapCheckId(), row.getContractId(), row.getContractNo(),
                stage, stage.label(), row.getAsOfDate(),
                DisplayFormat.nullableWon(row.getBasePremiumAmount()), DisplayFormat.nullableWon(row.getRefund12mAmount()),
                DisplayFormat.nullableWon(row.getComplianceDeductionAmount()), DisplayFormat.nullableWon(row.getLimitAmount()),
                DisplayFormat.nullableWon(row.getIncludedAmount()), DisplayFormat.nullableWon(row.getRemainingAmount()),
                DisplayFormat.rate(row.getUsagePct()), status, status.label(), row.getCapRuleSetId());
    }

    /** IF-API-31 응답의 capCheck — 저장된 판정(CapCheckSaveResult)을 같은 표시 형식(SIR-008)으로 변환한다. */
    public static CapCheckItemResponse from(CapCheckSaveResult saved, String contractNo) {
        CapCalculationResult r = saved.result();
        return new CapCheckItemResponse(
                saved.capCheckId(), r.contractId(), contractNo,
                r.paymentStage(), r.paymentStage().label(), r.asOfDate(),
                DisplayFormat.nullableWon(r.basePremiumAmount()), DisplayFormat.nullableWon(r.refund12mAmount()),
                DisplayFormat.nullableWon(r.complianceDeductionAmount()), DisplayFormat.nullableWon(r.limitAmount()),
                DisplayFormat.nullableWon(r.includedAmount()), DisplayFormat.nullableWon(r.remainingAmount()),
                DisplayFormat.rate(r.usagePct()), r.resultStatus(), r.resultStatus().label(), r.capRuleSetId());
    }
}
