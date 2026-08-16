package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : GA→FC 대사의 확정 지급 건 계약귀속 원자행
 *
 * @author yslee
 * @since 2026-08-13
 * @version 1.2
 */
@Getter
@Setter
public class GaFcActualSourceRow {

    private Long transactionAttributionId;
    private Long commissionTransactionId;
    private Long journalHeaderId;
    private Long contractId;
    private Long actualAgentId;
    private Long commissionItemId;
    private Integer actualInstallmentNo;
    private LocalDate settlementMonth;
    private LocalDate dueDate;
    private BigDecimal actualAmount;
    private String sourceBusinessKey;
}
