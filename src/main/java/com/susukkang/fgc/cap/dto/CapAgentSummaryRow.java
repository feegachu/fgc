package com.susukkang.fgc.cap.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/** CAP-W01 GA_TO_FC 설계사별 모니터링 집계 1행. 규제 판정에는 사용하지 않는다. */
@Getter
@Setter
public class CapAgentSummaryRow {
    private Long agentId;
    private String agentCode;
    private String agentName;
    private Long organizationId;
    private String organizationCode;
    private String organizationName;
    private long contractCount;
    private BigDecimal limitAmountTotal;
    private BigDecimal includedAmountTotal;
    private BigDecimal usagePct;
    private long violationCount;
    private long warningCount;
    private String worstContractNo;
    private BigDecimal worstUsagePct;
}
