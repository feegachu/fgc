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
        List<RefundRateTableResponse> refundRateTables,
        @Schema(description = "정책 편집 문서. expectedUpdatedAt과 자식 ID를 수정 요청에 사용")
        PolicyDraft editDocument,
        @Schema(description = "불변 정책 승인 이력, actionSeq 순")
        List<PolicyApprovalActionResponse> approvalActions,
        @Schema(description = "현재 사용자 역할·정책 상태·작성자를 반영한 동작")
        List<String> allowedActions,
        @Schema(description = "승인 근거 문서 참조. 기초서류 버전은 editDocument.basicDocumentVersion에서 조회")
        String approvalEvidenceRef,
        @Schema(description = "계산에 사용하는 계약일 적용 구간. 승계된 과거 버전은 후속 적용일 전날까지이며 원본 승인 기간은 보존")
        PolicyApplicationPeriod applicationPeriod
) {
    public PolicyDetailResponse(PolicyVersionResponse header, List<String> sourceRefs,
                                List<CommissionRuleResponse> commissionRules,
                                List<CapRuleSetResponse> capRuleSets,
                                List<RefundRateTableResponse> refundRateTables) {
        this(header, sourceRefs, commissionRules, capRuleSets, refundRateTables, null, List.of(), List.of(), null, null);
    }
}
