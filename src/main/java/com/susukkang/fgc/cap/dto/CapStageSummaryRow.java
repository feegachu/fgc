package com.susukkang.fgc.cap.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** CAP-W01 지급단계별 전체 검색범위 집계 1행. 지급단계 간 금액은 합산하지 않는다. */
@Getter
@Setter
public class CapStageSummaryRow {
    private String paymentStage;
    private long contractCount;
    private BigDecimal limitAmountTotal;
    private BigDecimal includedAmountTotal;
    private BigDecimal complianceDeductionAmountTotal;
    private BigDecimal usagePct;
    private long violationCount;
    private long warningCount;
    private String worstContractNo;
    private BigDecimal worstUsagePct;
}
