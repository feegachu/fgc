package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/** IF-API-41 대사 결과 상세 조회 모델. */
@Getter
@Setter
public class ReconciliationResultDetailRow {
    private Long reconciliationResultId;
    private Long reconciliationRunId;
    private String matchGroupKey;
    private Long contractId;
    private String contractNo;
    private Long commissionItemId;
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
    private String detailSnapshotJson;
    private OffsetDateTime createdAt;
}
