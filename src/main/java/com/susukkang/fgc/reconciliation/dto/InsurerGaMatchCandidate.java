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
        // 2026-08-13 yslee - FUN-048-04 저장 대상의 설계사 추적 필드 추가
        // 기존 코드: 후보 DTO에 예상·실제 설계사와 원수사 코드가 없어 reconciliation_result에 저장 불가
        // 문제: AGENT_MISMATCH 결과의 원천 식별값을 후속 저장 단계에서 재현할 수 없음
        // 개선: 예상·실제 내부 설계사 ID와 실제 원수사 코드를 후보에 보존
        Long expectedAgentId,
        Long actualAgentId,
        String actualSourceAgentCode,
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
