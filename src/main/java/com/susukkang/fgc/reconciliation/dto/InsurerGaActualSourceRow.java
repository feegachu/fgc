package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : 보험사→GA 대사의 실제 원수사 명세 귀속 원자행
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Getter
@Setter
public class InsurerGaActualSourceRow {

    private Long transactionAttributionId;
    private Long commissionTransactionId;
    private Long journalHeaderId;
    private Long contractId;
    private Long commissionItemId;
    private LocalDate settlementMonth;
    private LocalDate dueDate;
    private BigDecimal actualAmount;
    private String sourceBusinessKey;
    private String sourceAgentCode;
}
