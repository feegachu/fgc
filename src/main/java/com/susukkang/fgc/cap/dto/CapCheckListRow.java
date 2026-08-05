package com.susukkang.fgc.cap.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * CAP-W01 목록 1행. cap_check + insurance_contract(계약번호) 조인 투영.
 * 상세 판단근거(cap_check_detail)는 담지 않는다 — 목록에서는 필요 없고, 클릭 시 IF-API-31로 따로 연다.
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
