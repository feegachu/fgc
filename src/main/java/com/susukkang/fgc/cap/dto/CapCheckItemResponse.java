package com.susukkang.fgc.cap.dto;

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
        long refund12mAmount,
        long complianceDeductionAmount,
        long limitAmount,
        long includedAmount,
        long remainingAmount,
        String usagePct,
        CapResultStatus resultStatus,
        String resultStatusLabel,
        Long capRuleSetId
) {
    /** IF-API-30 목록 1행 — insurance_contract 조인으로 contractNo 를 이미 갖고 있다. */
    public static CapCheckItemResponse from(CapCheckListRow row) {
        PaymentStage stage = PaymentStage.valueOf(row.getPaymentStage());
        CapResultStatus status = CapResultStatus.valueOf(row.getResultStatus());
        return new CapCheckItemResponse(
                row.getCapCheckId(), row.getContractId(), row.getContractNo(),
                stage, stage.label(), row.getAsOfDate(),
                DisplayFormat.won(row.getBasePremiumAmount()), DisplayFormat.won(row.getRefund12mAmount()),
                DisplayFormat.won(row.getComplianceDeductionAmount()), DisplayFormat.won(row.getLimitAmount()),
                DisplayFormat.won(row.getIncludedAmount()), DisplayFormat.won(row.getRemainingAmount()),
                DisplayFormat.rate(row.getUsagePct()), status, status.label(), row.getCapRuleSetId());
    }
}
