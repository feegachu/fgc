package com.susukkang.fgc.reconciliation.dto;

import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : FUN-048-04 저장 단계에 전달할 보험사→GA 대사 후보
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
public record InsurerGaMatchCandidate(
        String matchGroupKey,
        Long contractId,
        Long commissionItemId,
        Integer installmentNo,
        LocalDate dueDate,
        LocalDate dueMonth,
        ReconciliationResultType resultType,
        BigDecimal expectedTotalAmount,
        BigDecimal actualTotalAmount,
        BigDecimal differenceAmount,
        String primaryReasonCode,
        List<String> secondaryReasonCodes,
        List<Long> scheduleLineIds,
        List<Long> transactionAttributionIds,
        List<Long> expectedJournalHeaderIds,
        List<Long> actualJournalHeaderIds
) {
    public InsurerGaMatchCandidate {
        secondaryReasonCodes = List.copyOf(secondaryReasonCodes);
        scheduleLineIds = List.copyOf(scheduleLineIds);
        transactionAttributionIds = List.copyOf(transactionAttributionIds);
        expectedJournalHeaderIds = List.copyOf(expectedJournalHeaderIds);
        actualJournalHeaderIds = List.copyOf(actualJournalHeaderIds);
    }
}
