package com.susukkang.fgc.policy.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * IF-API-10 정책 버전 상세 응답 (FGC-FUN-012·013 / POL-W01 탭).
 * capRuleSets·refundRateTables 는 복수다 — 정책버전당 지급단계·적용범위별로
 * 여러 행이 존재할 수 있다(uq_cap_rule_set_scope, uq_refund_table_scope).
 */
@Schema(description = "정책 버전 상세 — POL-W01 탭별 데이터")
public record PolicyDetailResponse(
        @Schema(description = "정책 버전 헤더")
        PolicyVersionResponse header,
        @Schema(description = "내부 근거 참조 목록(SRC-xx)")
        List<String> sourceRefs,
        @Schema(description = "수수료 규칙 탭")
        List<CommissionRuleResponse> commissionRules,
        @Schema(description = "1,200% 룰셋 탭 — 지급단계·적용범위별 복수")
        List<CapRuleSetResponse> capRuleSets,
        @Schema(description = "예상 해약환급률표 탭 — 상품·납입기간·채널별 복수")
        List<RefundRateTableResponse> refundRateTables
) {
}
