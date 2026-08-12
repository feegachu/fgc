package com.susukkang.fgc.policy.dto;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * IF-API-10 1,200% 룰셋 항목별 산입 판정 1행 (FGC-FUN-013 / POL-W01)
 */
@Schema(description = "1,200% 룰셋 항목별 산입 판정")
public record CapRuleItemResponse(
        @Schema(description = "룰셋 항목 판정 ID", example = "1")
        Long capRuleItemId,
        @Schema(description = "수수료 항목 코드", example = "NEWCOMER_SUPPORT")
        String itemCode,
        @Schema(description = "수수료 항목명", example = "신인 정착지원금")
        String itemName,
        @Schema(description = "산입 판정", allowableValues = {"INCLUDED", "EXCLUDED", "REVIEW_REQUIRED"})
        String inclusionStatus,
        @Schema(description = "제외 유형. EXCLUDED 일 때만 존재", nullable = true)
        String exclusionType,
        @Schema(description = "증빙 필요 여부")
        Boolean evidenceRequiredYn,
        @Schema(description = "귀속 방법", example = "DIRECT")
        String attributionMethod,
        @Schema(description = "판정 사유")
        String decisionReason
) {
    public static CapRuleItemResponse from(CapRuleItemRow row) {
        return new CapRuleItemResponse(
                row.getCapRuleItemId(), row.getItemCode(), row.getItemName(),
                row.getInclusionStatus(), row.getExclusionType(), row.getEvidenceRequiredYn(),
                row.getAttributionMethod(), row.getDecisionReason());
    }
}
