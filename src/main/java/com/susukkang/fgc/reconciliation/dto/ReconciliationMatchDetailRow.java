package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/** IF-API-41 원천 비교행 조회 모델. */
@Getter
@Setter
public class ReconciliationMatchDetailRow {
    private int matchSeq;
    private String matchRole;
    private Long scheduleLineId;
    private Long transactionAttributionId;
    private Long journalHeaderId;
    private Long contractId;
    private Long agentId;
    private Long commissionItemId;
    private Integer installmentNo;
    private LocalDate referenceDate;
    private BigDecimal matchedAmount;
}
