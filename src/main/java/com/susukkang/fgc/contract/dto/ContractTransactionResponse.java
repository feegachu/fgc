package com.susukkang.fgc.contract.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/v1/contracts/{id}/transactions 응답의 지급 건 1건
 */
@Getter
@Builder
public class ContractTransactionResponse {
    private final Long commissionTransactionId;
    private final LocalDate settlementMonth;
    private final String paymentStage;
    private final String paymentStageLabel;
    private final BigDecimal amount;
    private final String status;
    private final String statusLabel;
    private final String sourceType;
    private final BigDecimal attributionTotal;
    private final BigDecimal differenceAmount;
    private final List<ContractTransactionAttributionResponse> attributions;
}
