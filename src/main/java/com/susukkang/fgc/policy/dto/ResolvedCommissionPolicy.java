package com.susukkang.fgc.policy.dto;

import lombok.Builder;
import lombok.Getter;

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
public class ResolvedCommissionPolicy {

    private Long policyVersionId;                 // 적용된 수수료 정책 버전 ID
    private List<ResolvedCommissionRule> rules;   // 정책 버전에 포함된 수수료 규칙 목록
}
