package com.susukkang.fgc.cap.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * cap_check 1행 INSERT 파라미터
 * MyBatis useGeneratedKeys 로 capCheckId 를 되받음
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CapCheckInsertRow {
    private Long capCheckId;
    private Long validationRunId;
    private Long contractId;
    private String paymentStage;
    private Long capRuleSetId;
    private Long refundRateTableId;
    private String checkKind;
    private LocalDate asOfDate;
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
