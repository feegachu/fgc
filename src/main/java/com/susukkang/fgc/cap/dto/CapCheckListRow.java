package com.susukkang.fgc.cap.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CAP-W01 목록 1행. cap_check + insurance_contract(계약번호) 조인 투영
 */
@Getter
@Setter
public class CapCheckListRow {
    private Long capCheckId;
    private Long contractId;
    private String contractNo;
    private String paymentStage;
    private LocalDate asOfDate;
    private BigDecimal basePremiumAmount;
    private BigDecimal refund12mAmount;
    private BigDecimal complianceDeductionAmount;
    private BigDecimal limitAmount;
    private BigDecimal includedAmount;
    private BigDecimal remainingAmount;
    private BigDecimal usagePct;
    private String resultStatus;
    private Long capRuleSetId;
}
