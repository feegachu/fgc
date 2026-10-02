package com.susukkang.fgc.cap.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * cap_rule_item 1건 + 항목코드. 수수료 항목별 산입·제외·검토필요 분류
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CapRuleItemView {
    private Long capRuleItemId;
    private Long commissionItemId;
    private String itemCode;
    private String itemName;
    private String inclusionStatus;
    private String exclusionType;
    private boolean evidenceRequiredYn;
    private String attributionMethod;
    private String decisionReason;
}
