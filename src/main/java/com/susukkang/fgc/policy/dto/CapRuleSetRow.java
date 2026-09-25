package com.susukkang.fgc.policy.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public static List<CapRuleSetRow> fromDetails(List<CapRuleSetDetailRow> details) {
        Map<Long, CapRuleSetRow> ruleSets = new LinkedHashMap<>();
        for (CapRuleSetDetailRow detail : details) {
            CapRuleSetRow ruleSet = ruleSets.computeIfAbsent(detail.capRuleSetId(), id -> {
                CapRuleSetRow row = new CapRuleSetRow();
                row.capRuleSetId = id;
                row.paymentStage = detail.paymentStage();
                row.contractDateFrom = detail.contractDateFrom();
                row.contractDateTo = detail.contractDateTo();
                row.firstYearMonths = detail.firstYearMonths();
                row.premiumMultiplier = detail.premiumMultiplier();
                row.complianceDeductionPct = detail.complianceDeductionPct();
                row.refundAdditionCondition = detail.refundAdditionCondition();
                row.warningUsagePct = detail.warningUsagePct();
                row.items = new ArrayList<>();
                return row;
            });
            if (detail.capRuleItemId() != null) {
                CapRuleItemRow item = new CapRuleItemRow();
                item.setCapRuleItemId(detail.capRuleItemId());
                item.setItemCode(detail.itemCode());
                item.setItemName(detail.itemName());
                item.setInclusionStatus(detail.inclusionStatus());
                item.setExclusionType(detail.exclusionType());
                item.setEvidenceRequiredYn(detail.evidenceRequiredYn());
                item.setAttributionMethod(detail.attributionMethod());
                item.setDecisionReason(detail.decisionReason());
                ruleSet.items.add(item);
            }
        }
        return List.copyOf(ruleSets.values());
    }
}
