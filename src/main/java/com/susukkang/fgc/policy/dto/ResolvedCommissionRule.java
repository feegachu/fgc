package com.susukkang.fgc.policy.dto;

import com.susukkang.fgc.common.code.AgentRankCode;
import com.susukkang.fgc.common.code.CalculationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 설명 : 계약에 적용할 수수료 정책 규칙 정보를 전달하는 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedCommissionRule {

    private Long commissionRuleId;        // 수수료 규칙 ID
    private Long commissionItemId;        // 수수료 항목 ID
    private Long organizationId;          // 조직 ID
    private AgentRankCode agentRankCode;         // 수수료 수령 대상 설계사 직급 코드
    private Integer installmentFrom;      // 규칙 적용 시작 회차
    private Integer installmentTo;        // 규칙 적용 종료 회차
    private String basisCode;             // 수수료 계산 기준 코드
    private CalculationType calculationType;       // 계산 방식(RATE: 정률, FIXED: 정액)
    private BigDecimal ratePct;           // 적용 요율(%), 정률 계산 시 사용
    private BigDecimal fixedAmount;       // 적용 정액, 정액 계산 시 사용
    private Integer roundingScale;      // 반올림 후 유지할 소수점 자릿수
    private RoundingMode roundingMode;        // 반올림 방식(HALF_UP)
    private String paymentConditionCode;  // 수수료 지급 조건 코드
}
