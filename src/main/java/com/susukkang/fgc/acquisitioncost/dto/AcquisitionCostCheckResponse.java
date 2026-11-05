package com.susukkang.fgc.acquisitioncost.dto;

import com.susukkang.fgc.common.code.PaymentStage;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * FUN-062 검증 결과. 이 문서 그대로 calculation_snapshot에 저장하고 조회할 때 다시 계산하지 않는다.
 * 금액 null은 자료 없음이고 0은 실제 0원이다. 선지급과 유지관리는 같은 기준액을 각자 비교하며 서로 차감하지 않는다.
 */
public record AcquisitionCostCheckResponse(
        Long acquisitionCostCheckId,
        Long contractId,
        String contractNo,
        String insurerName,
        String productName,
        String offeringVersion,
        String basicDocumentVersion,
        LocalDate basicDocumentDate,
        LocalDate salesStartDate,
        LocalDate contractDate,
        String feeRegimeCode,
        PaymentStage paymentStage,
        String paymentStageLabel,
        AcquisitionCostResultStatus resultStatus,
        String resultStatusLabel,
        @Schema(nullable = true, description = "계약체결비용 기준. 룰셋을 고르지 못했거나 적용 대상이 아니면 null")
        AcquisitionCostBasis acquisitionCost,
        @Schema(nullable = true, description = "선지급 총액 한도 결과. 적용 대상이 아니거나 룰셋을 고르지 못하면 null")
        AcquisitionCostItem upfront,
        @Schema(nullable = true, description = "유지관리 총액 한도 결과. 간접지원경비와 장기유지수수료를 포함한다")
        AcquisitionCostItem maintenance,
        @Schema(nullable = true, description = "유지관리 총액에 포함된 간접지원경비. 별도 한도를 두지 않는다")
        BigDecimal indirectSupportAmount,
        @Schema(nullable = true, description = "분류하지 못해 판정에 넣지 않은 금액")
        BigDecimal unclassifiedAmount,
        List<AcquisitionCostExclusionLine> exclusions,
        List<AcquisitionCostLineGroup> lineGroups,
        List<AcquisitionCostReason> reasons,
        @Schema(nullable = true) AcquisitionCostPolicyRef limitPolicy,
        @Schema(nullable = true) AcquisitionCostPolicyRef paymentPolicy,
        @Schema(nullable = true) Long scheduleHeaderId,
        @Schema(nullable = true) Integer scheduleVersionNo,
        String calcRuleVersion,
        OffsetDateTime checkedAt
) {

    public AcquisitionCostCheckResponse stored(long id, OffsetDateTime at) {
        return new AcquisitionCostCheckResponse(id, contractId, contractNo, insurerName, productName, offeringVersion,
                basicDocumentVersion, basicDocumentDate, salesStartDate, contractDate, feeRegimeCode, paymentStage,
                paymentStageLabel, resultStatus, resultStatusLabel, acquisitionCost, upfront, maintenance,
                indirectSupportAmount, unclassifiedAmount, exclusions, lineGroups, reasons, limitPolicy, paymentPolicy,
                scheduleHeaderId, scheduleVersionNo, calcRuleVersion, at);
    }

    /** 계약체결비용 기준액의 입력. 규정이 정한 값이 아니라 정책에 등록한 값으로 계산한다. */
    public record AcquisitionCostBasis(
            @Schema(nullable = true, description = "기준액(원). 입력이 부족하면 null") BigDecimal amount,
            @Schema(nullable = true) String basisCode,
            @Schema(nullable = true) BigDecimal premiumAmount,
            @Schema(nullable = true) BigDecimal ratePct,
            @Schema(nullable = true, description = "정책에 등록한 값의 출처 설명") String description
    ) {
    }

    /** 한도 하나의 결과. 기준액 × 적용률 = 한도, 예정 금액의 합 = 계산액. */
    public record AcquisitionCostItem(
            String limitCode,
            String label,
            @Schema(nullable = true) BigDecimal baseAmount,
            @Schema(nullable = true) BigDecimal limitPct,
            @Schema(nullable = true) BigDecimal calculatedAmount,
            @Schema(nullable = true) BigDecimal limitAmount,
            @Schema(nullable = true) BigDecimal usagePct,
            AcquisitionCostResultStatus status,
            String statusLabel,
            @Schema(nullable = true, description = "누적이 처음 한도를 넘는 회차") Integer exceededFromInstallmentNo,
            String reason
    ) {
    }

    /** 제4-32조제7항제3호 후보 금액. 요건과 증빙을 갖춘 행만 excluded=true다. */
    public record AcquisitionCostExclusionLine(
            Long scheduleLineId,
            Integer installmentNo,
            String itemCode,
            String itemName,
            BigDecimal amount,
            boolean excluded,
            @Schema(nullable = true) String evidenceRef,
            String reason
    ) {
    }

    /** 계산 근거. 예정 회차를 항목과 산입 위치별로 묶는다. */
    public record AcquisitionCostLineGroup(
            String bucket,
            String bucketLabel,
            String itemCode,
            String itemName,
            @Schema(nullable = true) String feeComponentType,
            Integer installmentFrom,
            Integer installmentTo,
            Integer lineCount,
            BigDecimal amount
    ) {
    }

    public record AcquisitionCostReason(String code, String message) {
    }

    public record AcquisitionCostPolicyRef(
            Long policyVersionId,
            String policyCode,
            Integer versionNo,
            String policyName,
            String sourceClass,
            String sourceClassLabel,
            List<String> regulationRefs,
            List<String> sourceRefs
    ) {
    }
}
