package com.susukkang.fgc.cap.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * cap_check 1행 조회 결과(투영). details/calculationSnapshot 은 별도로 조회·역직렬화해서
 * CapCheckService 가 CapCalculationResult 로 다시 조립한다.
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
