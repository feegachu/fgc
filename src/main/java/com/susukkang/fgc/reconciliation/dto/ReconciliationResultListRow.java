package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** IF-API-40 대사 결과 목록 조회 모델. */
@Getter
@Setter
public class ReconciliationResultListRow {
    private Long reconciliationResultId;
    private String contractNo;
    private String commissionItemCode;
    private String commissionItemName;
    private Integer installmentNo;
    private Long expectedAgentId;
    private String expectedAgentCode;
    private String expectedAgentName;
    private String expectedOrganizationName;
    private Long actualAgentId;
    private String actualAgentCode;
    private String actualAgentName;
    private String actualOrganizationName;
    private String actualSourceAgentCode;
    private BigDecimal expectedTotalAmount;
    private BigDecimal actualTotalAmount;
    private BigDecimal differenceAmount;
    private String resultType;
    private String primaryReasonCode;
    private String secondaryReasonCodesCsv;
    private OffsetDateTime createdAt;
}
