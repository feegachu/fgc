package com.susukkang.fgc.contract.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * GET /api/v1/contracts/{id}/transactions 응답의 지급 건 1건.
 *
 * attributionTotal은 "이 계약"에 귀속된 금액 합계(attributions 리스트의 합)이고,
 * differenceAmount는 이 지급 건 "전체"(계약 무관) 귀속 합계와 amount의 차액이다 —
 * 정착지원금·공통비처럼 한 지급 건이 여러 계약에 나뉘어 귀속되는 경우 attributionTotal이
 * amount보다 작은 게 정상이라, 그 둘을 직접 빼면 안 된다(코드리뷰 반영).
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
