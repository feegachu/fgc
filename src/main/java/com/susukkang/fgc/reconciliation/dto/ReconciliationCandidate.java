package com.susukkang.fgc.reconciliation.dto;

import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * 설명 : 양방향 대사 후보가 결과 저장 단계에 제공하는 공통 계약
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
public interface ReconciliationCandidate {

    String matchGroupKey();
    Long contractId();
    Long expectedAgentId();
    Long actualAgentId();
    Long commissionItemId();
    Integer installmentNo();
    Integer actualInstallmentNo();
    LocalDate dueDate();
    LocalDate dueMonth();
    ReconciliationResultType resultType();
    BigDecimal expectedTotalAmount();
    BigDecimal actualTotalAmount();
    BigDecimal differenceAmount();
    String primaryReasonCode();
    List<String> secondaryReasonCodes();
    List<Long> scheduleLineIds();
    List<Long> transactionAttributionIds();
    List<Long> expectedJournalHeaderIds();
    List<Long> actualJournalHeaderIds();
    List<ReconciliationMatchSource> sourceMatches();

    default String actualSourceAgentCode() {
        return null;
    }
}
