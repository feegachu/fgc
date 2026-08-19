package com.susukkang.fgc.validation.dto;

/**
 * IF-API-47 응답의 validation_target 1건.
 * selectionStatusLabel은 화면정의서 VRUN-W02 ③(선정·제외·검토필요) 표기 — 서버가 만든다(SIR-008).
 */
public record ValidationTargetItemResponse(
        Long validationTargetId,
        String contractNo,
        String selectionStatus,
        String selectionStatusLabel,
        String productName,
        String offeringVersion,
        Long refundRateTableId,
        String selectionReason
) {
    public static ValidationTargetItemResponse from(ValidationTargetListRow row) {
        return new ValidationTargetItemResponse(
                row.getValidationTargetId(),
                row.getContractNo(),
                row.getSelectionStatus(),
                selectionStatusLabel(row.getSelectionStatus()),
                row.getProductName(),
                row.getOfferingVersion(),
                row.getRefundRateTableId(),
                row.getSelectionReason());
    }

    private static String selectionStatusLabel(String selectionStatus) {
        return switch (selectionStatus) {
            case "SELECTED" -> "선정";
            case "EXCLUDED" -> "제외";
            case "REVIEW_REQUIRED" -> "검토필요";
            default -> selectionStatus;
        };
    }
}
