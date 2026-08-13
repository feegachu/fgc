package com.susukkang.fgc.policy.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * POL-W01 수수료 규칙 탭 1행. commission_rule + insurer/product/commission_item 이름 조인 투영.
 * insurer/product/조직/직급이 NULL 이면 "전체 적용" 규칙이다.
 */
@Getter
@Setter
public class CommissionRuleRow {
    private Long commissionRuleId;
    private String paymentStage;
    private String insurerName;
    private String productName;
    private String agentRankCode;
    private String itemCode;
    private String itemName;
    private String feeComponentType;
    private Integer installmentFrom;
    private Integer installmentTo;
    private String calculationType;
    private String basisCode;
    private BigDecimal ratePct;
    private BigDecimal fixedAmount;
    private Integer priorityNo;
}
