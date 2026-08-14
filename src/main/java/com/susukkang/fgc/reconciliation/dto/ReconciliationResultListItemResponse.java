package com.susukkang.fgc.reconciliation.dto;

import com.susukkang.fgc.reconciliation.domain.ReconciliationReasonCode;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;

/** IF-API-40 대사 결과 목록 항목. */
public record ReconciliationResultListItemResponse(
        Long reconciliationResultId,
        String contractNo,
        String commissionItemCode,
        String commissionItemName,
        Integer installmentNo,
        Agent expectedAgent,
        Agent actualAgent,
        String actualSourceAgentCode,
        BigDecimal expectedTotalAmount,
        BigDecimal actualTotalAmount,
        BigDecimal differenceAmount,
        String resultType,
        String resultTypeLabel,
        String primaryReasonCode,
        ReconciliationReasonResponse primaryReason,
        List<ReconciliationReasonResponse> secondaryReasons,
        OffsetDateTime createdAt
) {
    public static ReconciliationResultListItemResponse from(ReconciliationResultListRow row) {
        return new ReconciliationResultListItemResponse(
                row.getReconciliationResultId(), row.getContractNo(),
                row.getCommissionItemCode(), row.getCommissionItemName(), row.getInstallmentNo(),
                new Agent(row.getExpectedAgentId(), row.getExpectedAgentCode(), row.getExpectedAgentName(),
                        row.getExpectedOrganizationName()),
                new Agent(row.getActualAgentId(), row.getActualAgentCode(), row.getActualAgentName(),
                        row.getActualOrganizationName()),
                row.getActualSourceAgentCode(), row.getExpectedTotalAmount(), row.getActualTotalAmount(),
                row.getDifferenceAmount(), row.getResultType(),
                ReconciliationReasonCode.resultTypeLabel(row.getResultType()),
                row.getPrimaryReasonCode(),
                ReconciliationReasonResponse.from(row.getPrimaryReasonCode()),
                reasonResponses(row.getSecondaryReasonCodesCsv()), row.getCreatedAt());
    }

    static List<ReconciliationReasonResponse> reasonResponses(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .filter(code -> !code.isBlank())
                .map(ReconciliationReasonResponse::from)
                .toList();
    }

    public record Agent(Long agentId, String agentCode, String agentName, String organizationName) {
    }
}
