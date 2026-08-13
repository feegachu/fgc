package com.susukkang.fgc.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 계약 기준 commission_transaction + transaction_attribution(+ agent) 조인 조회 결과 1행
 */
@Getter
@Setter
public class ContractTransactionAttributionRow {
    private Long commissionTransactionId;
    private LocalDate settlementMonth;
    private String paymentStage;
    private BigDecimal amount;
    private String status;
    private String sourceType;
    private Long transactionAttributionId;
    private BigDecimal attributedAmount;
    private LocalDate attributionDate;
    private String inclusionStatus;
    private Long agentId;
    private String agentName;
    private String agentCode;
}
