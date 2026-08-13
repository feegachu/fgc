package com.susukkang.fgc.contract.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 계약 기준 reconciliation_result 조회 결과 1행(CONT-W02 탭6 "지급·대사" —
 * 화면정의서 :565가 이 탭의 데이터소스로 commission_transaction과 함께 명시).
 */
@Getter
@Setter
public class ContractReconciliationRow {
    private Long reconciliationResultId;
    private String matchGroupKey;
    private String resultType;
    private BigDecimal expectedTotalAmount;
    private BigDecimal actualTotalAmount;
    private BigDecimal differenceAmount;
    private String primaryReasonCode;
    private Integer installmentNo;
    private Long commissionItemId;
    private OffsetDateTime createdAt;
}
