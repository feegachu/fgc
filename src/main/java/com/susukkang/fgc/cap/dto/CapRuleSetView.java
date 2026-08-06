package com.susukkang.fgc.cap.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * cap_rule_set 1건. 계약일·지급단계·보험사·상품군·채널 범위로 조회한 가장 구체적인 룰셋
 */
@Getter
@Setter
public class CapRuleSetView {
    private Long capRuleSetId;
    private Long policyVersionId;
    private String paymentStage;
    private LocalDate contractDateFrom;
    private LocalDate contractDateTo;
    private Long insurerId;
    private String productGroupCode;
    private String channelCode;
    private Integer firstYearMonths;
    private BigDecimal premiumMultiplier;
    private BigDecimal complianceDeductionPct;
    private String refundAdditionCondition;
    private BigDecimal warningUsagePct;
}
