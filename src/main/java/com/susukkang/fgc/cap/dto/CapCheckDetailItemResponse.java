package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.util.DisplayFormat;

/**
 * IF-API-31 details[] 1행. 필드명(commissionItemName/classificationSnapshot 등)은 인터페이스정의서
 * 4-2절 표에 명시된 이름을 그대로 쓴다 — 화면이 그 이름으로 바인딩한다.
 */
public record CapCheckDetailItemResponse(
        int detailSeq,
        String commissionItemName,
        String classificationSnapshot,
        String classificationSnapshotLabel,
        long amount,
        String decisionReason,
        String evidenceRef
) {
    public static CapCheckDetailItemResponse from(CapCheckDetailLine line) {
        return new CapCheckDetailItemResponse(
                line.detailSeq(), line.itemName(), line.classification(), label(line.classification()),
                DisplayFormat.won(line.amount()), line.decisionReason(), line.evidenceRef());
    }

    private static String label(String classification) {
        return switch (classification) {
            case "INCLUDED" -> "산입";
            case "EXCLUDED" -> "제외";
            case "REVIEW_REQUIRED" -> "검토필요";
            default -> classification;
        };
    }
}
