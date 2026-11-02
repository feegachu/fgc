package com.susukkang.fgc.policy.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.util.DisplayFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.List;

/**
 * IF-API-10 1,200% 룰셋 탭 1행 (FGC-FUN-012·013 / POL-W01).
 * 준법경영비 공제(compliance_deduction_pct)는 원수사→GA 게이지에만 존재한다
 * (REG-10, DB CHECK ck_cap_compliance_stage 강제).
 */
@Schema(description = "1,200% 룰셋")
public record CapRuleSetResponse(
        @Schema(description = "룰셋 ID", example = "1")
        Long capRuleSetId,
        @Schema(description = "지급단계 — 두 게이지는 분리 계산한다")
        PaymentStage paymentStage,
        @Schema(description = "지급단계 한글 라벨", example = "원수사→GA")
        String paymentStageLabel,
        @Schema(description = "적용 계약일 시작", example = "2026-07-01")
        LocalDate contractDateFrom,
        @Schema(description = "적용 계약일 종료. 없으면 null(계속 적용)", nullable = true)
        LocalDate contractDateTo,
        @Schema(description = "초년도 판단기간(월)", example = "12")
        Integer firstYearMonths,
        @Schema(description = "한도 배수. 문자열로 정밀도 보존", example = "12.0000")
        String premiumMultiplier,
        @Schema(description = "준법경영비 공제율(%). GA→설계사는 항상 0", example = "3.0000")
        String complianceDeductionPct,
        @Schema(description = "환급금 가산 조건", allowableValues = {"NONE", "STANDARD_DEDUCTION_80"})
        String refundAdditionCondition,
        @Schema(description = "주의 기준 사용률(%). 90을 코드에 박지 말고 이 값을 읽는다(COR-004)", example = "90.0000")
        String warningUsagePct,
        @Schema(description = "항목별 산입·제외 판정 목록")
        List<CapRuleItemResponse> items
) {
    public static CapRuleSetResponse from(CapRuleSetRow row) {
        PaymentStage stage = PaymentStage.valueOf(row.getPaymentStage());
        return new CapRuleSetResponse(
                row.getCapRuleSetId(), stage, stage.label(),
                row.getContractDateFrom(), row.getContractDateTo(), row.getFirstYearMonths(),
                DisplayFormat.rate(row.getPremiumMultiplier()),
                DisplayFormat.rate(row.getComplianceDeductionPct()),
                row.getRefundAdditionCondition(),
                DisplayFormat.rate(row.getWarningUsagePct()),
                row.getItems() == null ? List.of()
                        : row.getItems().stream().map(CapRuleItemResponse::from).toList());
    }
}
