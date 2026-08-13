package com.susukkang.fgc.policy.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * POL-W01 1,200% 룰셋 탭 1행. cap_rule_set + 항목별 산입 판정(cap_rule_item) 중첩 투영.
 * 정책버전당 지급단계·계약일범위·적용범위별로 복수 행이 존재할 수 있다(uq_cap_rule_set_scope).
 */
@Getter
@Setter
public class CapRuleSetRow {
    private Long capRuleSetId;
    private String paymentStage;
    private LocalDate contractDateFrom;
    private LocalDate contractDateTo;
    private Integer firstYearMonths;
    private BigDecimal premiumMultiplier;
    private BigDecimal complianceDeductionPct;
    private String refundAdditionCondition;
    private BigDecimal warningUsagePct;
    private List<CapRuleItemRow> items;
}
