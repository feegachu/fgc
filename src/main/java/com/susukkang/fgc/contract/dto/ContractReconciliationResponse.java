package com.susukkang.fgc.contract.dto;

import com.susukkang.fgc.common.code.ReconResultType;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** CONT-W02 탭6 "지급·대사" 응답의 대사 결과 1건. */
public record ContractReconciliationResponse(
        Long reconciliationResultId,
        String matchGroupKey,
        ReconResultType resultType,
        String resultTypeLabel,
        BigDecimal expectedTotalAmount,
        BigDecimal actualTotalAmount,
        BigDecimal differenceAmount,
        String primaryReasonCode,
        Integer installmentNo,
        Long commissionItemId,
        OffsetDateTime createdAt
) {
    public static ContractReconciliationResponse from(ContractReconciliationRow row) {
        ReconResultType resultType = ReconResultType.valueOf(row.getResultType());
        return new ContractReconciliationResponse(
                row.getReconciliationResultId(), row.getMatchGroupKey(),
                resultType, resultType.label(),
                row.getExpectedTotalAmount(), row.getActualTotalAmount(), row.getDifferenceAmount(),
                row.getPrimaryReasonCode(), row.getInstallmentNo(), row.getCommissionItemId(),
                row.getCreatedAt());
    }
}
