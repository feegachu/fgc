package com.susukkang.fgc.policy.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;

/**
 * 설명 : 계약에 적용할 수수료 정책 규칙 정보를 전달하는 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
@Getter
@Builder
public class ResolvedCommissionRule {

    private Long commissionRuleId;        // 수수료 규칙 ID
    private Long commissionItemId;        // 수수료 항목 ID
    private String agentRankCode;         // 수수료 수령 대상 설계사 직급 코드

    private Integer installmentFrom;      // 규칙 적용 시작 회차
    private Integer installmentTo;        // 규칙 적용 종료 회차

    private String basisCode;             // 수수료 계산 기준 코드
    private String calculationType;       // 계산 방식(RATE: 정률, FIXED: 정액)
    private BigDecimal ratePct;           // 적용 요율(%), 정률 계산 시 사용
    private BigDecimal fixedAmount;       // 적용 정액, 정액 계산 시 사용
    private String paymentConditionCode;  // 수수료 지급 조건 코드
}
