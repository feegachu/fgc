package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.util.DisplayFormat;

/** CAP-W01 Stage Gauge 응답. INSURER_TO_GA와 GA_TO_FC를 각각 한 행으로 반환한다. */
public record CapStageSummaryResponse(
        PaymentStage paymentStage,
        String paymentStageLabel,
        long contractCount,
        long limitAmountTotal,
        long includedAmountTotal,
        long complianceDeductionAmountTotal,
        String usagePct,
        long violationCount,
        long warningCount,
        long reviewRequiredCount,
        String worstContractNo,
        String worstUsagePct
) {
    public static CapStageSummaryResponse from(CapStageSummaryRow row) {
        PaymentStage stage = PaymentStage.valueOf(row.getPaymentStage());
        return new CapStageSummaryResponse(
                stage,
                stage.label(),
                row.getContractCount(),
                DisplayFormat.won(row.getLimitAmountTotal()),
                DisplayFormat.won(row.getIncludedAmountTotal()),
                DisplayFormat.won(row.getComplianceDeductionAmountTotal()),
                DisplayFormat.rate(row.getUsagePct()),
                row.getViolationCount(),
                row.getWarningCount(),
                row.getReviewRequiredCount(),
                row.getWorstContractNo(),
                DisplayFormat.rate(row.getWorstUsagePct()));
    }
}
