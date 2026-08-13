package com.susukkang.fgc.contract.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * GET /api/v1/contracts/{id}/transactions 응답의 귀속행 1건.
 */
@Getter
@Builder
public class ContractTransactionAttributionResponse {
    private final Long transactionAttributionId;
    private final BigDecimal attributedAmount;
    private final LocalDate attributionDate;
    private final String inclusionStatus;
    private final String inclusionStatusLabel;
    private final Long agentId;
    private final String agentName;
    private final String agentCode;
}
