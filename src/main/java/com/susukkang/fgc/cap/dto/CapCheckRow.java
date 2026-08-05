package com.susukkang.fgc.cap.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * cap_check 1행 조회 결과
 */
@Getter
@Setter
public class CapCheckRow {
    private Long capCheckId;
    private Long contractId;
    private String paymentStage;
    private String checkKind;
    private LocalDate asOfDate;
    private Long capRuleSetId;
    private Long refundRateTableId;
    private BigDecimal basePremiumAmount;
    private BigDecimal refund12mAmount;
    private BigDecimal complianceDeductionAmount;
    private BigDecimal limitAmount;
    private BigDecimal includedAmount;
    private BigDecimal remainingAmount;
    private BigDecimal usagePct;
    private String resultStatus;
    private String calculationSnapshotJson;
}
