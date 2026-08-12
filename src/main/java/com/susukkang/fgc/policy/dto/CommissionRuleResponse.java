package com.susukkang.fgc.policy.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.util.DisplayFormat;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * IF-API-10 수수료 규칙 탭 1행 (FGC-FUN-012·013 / POL-W01)
 */
@Schema(description = "수수료 규칙")
public record CommissionRuleResponse(
        @Schema(description = "수수료 규칙 ID", example = "1")
        Long commissionRuleId,
        @Schema(description = "지급단계")
        PaymentStage paymentStage,
        @Schema(description = "지급단계 한글 라벨", example = "GA→설계사")
        String paymentStageLabel,
        @Schema(description = "보험회사명. null 이면 전체 적용", nullable = true)
        String insurerName,
        @Schema(description = "상품명. null 이면 전체 적용", nullable = true)
        String productName,
        @Schema(description = "설계사 직급 코드. null 이면 전체 적용", example = "FC", nullable = true)
        String agentRankCode,
        @Schema(description = "수수료 항목 코드", example = "BASE_COMMISSION")
        String itemCode,
        @Schema(description = "수수료 항목명", example = "FC 기본수수료")
        String itemName,
        @Schema(description = "수수료 구성 유형", example = "CURRENT")
        String feeComponentType,
        @Schema(description = "회차 시작", example = "1")
        Integer installmentFrom,
        @Schema(description = "회차 종료", example = "12")
        Integer installmentTo,
        @Schema(description = "계산방식", allowableValues = {"RATE", "FIXED"})
        String calculationType,
        @Schema(description = "기준금액 코드", example = "MONTHLY_PREMIUM")
        String basisCode,
        @Schema(description = "요율(%). 문자열로 정밀도 보존, FIXED 규칙이면 null", example = "650.000000", nullable = true)
        String ratePct,
        @Schema(description = "정액(원). RATE 규칙이면 null", nullable = true)
        Long fixedAmount,
        @Schema(description = "우선순위(작을수록 우선)", example = "100")
        Integer priorityNo
) {
    public static CommissionRuleResponse from(CommissionRuleRow row) {
        PaymentStage stage = PaymentStage.valueOf(row.getPaymentStage());
        return new CommissionRuleResponse(
                row.getCommissionRuleId(), stage, stage.label(),
                row.getInsurerName(), row.getProductName(), row.getAgentRankCode(),
                row.getItemCode(), row.getItemName(), row.getFeeComponentType(),
                row.getInstallmentFrom(), row.getInstallmentTo(),
                row.getCalculationType(), row.getBasisCode(),
                DisplayFormat.rate(row.getRatePct()),
                row.getFixedAmount() == null ? null : DisplayFormat.won(row.getFixedAmount()),
                row.getPriorityNo());
    }
}
