package com.susukkang.fgc.policy.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.PolicyType;
import com.susukkang.fgc.schedule.code.ScheduleRegime;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 설명 : 계약과 지급 단계에 적용할 수수료 정책 및 규칙 목록을 전달하는 DTO
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ResolvedCommissionPolicy {
    private Long policyVersionId;                 // 적용된 수수료 정책 버전 ID
    private PolicyType policyType;                // 정책 유형 (현행,4년,7년,환수,환수율표 등 )
    private PaymentStage paymentStage;            // 정책이 적용되는 지급 단계
    private ScheduleRegime scheduleRegime;         // 상품 판매버전에 적용되는 수수료 체계
    private List<ResolvedCommissionRule> rules;   // 지급 단계에 적용할 수수료 규칙 목록
}
