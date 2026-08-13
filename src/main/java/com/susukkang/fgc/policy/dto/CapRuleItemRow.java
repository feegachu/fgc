package com.susukkang.fgc.policy.dto;

import lombok.Getter;
import lombok.Setter;

/**
 * POL-W01 1,200% 룰셋 탭의 항목별 산입 판정 1행. cap_rule_item + commission_item 이름 조인 투영
 */
@Getter
@Setter
public class CapRuleItemRow {
    private Long capRuleItemId;
    private String itemCode;
    private String itemName;
    private String inclusionStatus;
    private String exclusionType;
    private Boolean evidenceRequiredYn;
    private String attributionMethod;
    private String decisionReason;
}
