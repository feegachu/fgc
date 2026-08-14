package com.susukkang.fgc.reconciliation.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.susukkang.fgc.reconciliation.domain.ReconciliationReasonCode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/** IF-API-41 대사 결과 상세 응답. */
public record ReconciliationResultDetailResponse(
        Long reconciliationResultId,
        Long reconciliationRunId,
        String matchGroupKey,
        Long contractId,
        String contractNo,
        Long commissionItemId,
        String commissionItemCode,
        String commissionItemName,
        Integer installmentNo,
        ReconciliationResultListItemResponse.Agent expectedAgent,
        ReconciliationResultListItemResponse.Agent actualAgent,
        String actualSourceAgentCode,
        BigDecimal expectedTotalAmount,
        BigDecimal actualTotalAmount,
        BigDecimal differenceAmount,
        String resultType,
        String resultTypeLabel,
        ReconciliationReasonResponse primaryReason,
        List<ReconciliationReasonResponse> secondaryReasons,
        List<Match> matches,
        JsonNode detailSnapshot,
        OffsetDateTime createdAt
) {
    public static ReconciliationResultDetailResponse from(
            ReconciliationResultDetailRow row,
            List<ReconciliationMatchDetailRow> matches,
            JsonNode detailSnapshot
    ) {
        return new ReconciliationResultDetailResponse(
                row.getReconciliationResultId(), row.getReconciliationRunId(), row.getMatchGroupKey(),
                row.getContractId(), row.getContractNo(), row.getCommissionItemId(),
                row.getCommissionItemCode(), row.getCommissionItemName(), row.getInstallmentNo(),
                new ReconciliationResultListItemResponse.Agent(row.getExpectedAgentId(), row.getExpectedAgentCode(),
                        row.getExpectedAgentName(), row.getExpectedOrganizationName()),
                new ReconciliationResultListItemResponse.Agent(row.getActualAgentId(), row.getActualAgentCode(),
                        row.getActualAgentName(), row.getActualOrganizationName()),
                row.getActualSourceAgentCode(), row.getExpectedTotalAmount(), row.getActualTotalAmount(),
                row.getDifferenceAmount(), row.getResultType(),
                ReconciliationReasonCode.resultTypeLabel(row.getResultType()),
                ReconciliationReasonResponse.from(row.getPrimaryReasonCode()),
                ReconciliationResultListItemResponse.reasonResponses(row.getSecondaryReasonCodesCsv()),
                matches.stream().map(Match::from).toList(), detailSnapshot, row.getCreatedAt());
    }

    public record Match(
            int matchSeq,
            String matchRole,
            Long scheduleLineId,
            Long transactionAttributionId,
            Long journalHeaderId,
            Long contractId,
            Long agentId,
            Long commissionItemId,
            Integer installmentNo,
            LocalDate referenceDate,
            BigDecimal matchedAmount
    ) {
        static Match from(ReconciliationMatchDetailRow row) {
            return new Match(row.getMatchSeq(), row.getMatchRole(), row.getScheduleLineId(),
                    row.getTransactionAttributionId(), row.getJournalHeaderId(), row.getContractId(),
                    row.getAgentId(), row.getCommissionItemId(), row.getInstallmentNo(),
                    row.getReferenceDate(), row.getMatchedAmount());
        }
    }
}
