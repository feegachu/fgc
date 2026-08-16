package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.List;

/**
 * 설명 : reconciliation_result 멱등 저장 입력 모델
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@Getter
@Setter
public class ReconciliationResultInsertRow {

    private Long reconciliationResultId;
    private Long reconciliationRunId;
    private String matchGroupKey;
    private Long contractId;
    private Long expectedAgentId;
    private Long actualAgentId;
    private String actualSourceAgentCode;
    private Long commissionItemId;
    private Integer installmentNo;
    private String resultType;
    private BigDecimal expectedTotalAmount;
    private BigDecimal actualTotalAmount;
    private BigDecimal differenceAmount;
    private String primaryReasonCode;
    private List<String> secondaryReasonCodes;
    private String detailSnapshotJson;
}
