package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.util.DisplayFormat;

/**
 * IF-API-31 details[] 1행 (api-spec.md: detailSeq, commissionItemName, classificationSnapshot,
 * amount, decisionReason, evidenceRef). 내부 매퍼 조회 결과인 CapCheckDetailLine을 API 응답
 * 필드명·형식(SIR-008)에 맞게 변환한다.
 */
public record CapCheckDetailResponse(
        int detailSeq,
        String commissionItemName,
        String classificationSnapshot,
        long amount,
        String decisionReason,
        String evidenceRef
) {
    public static CapCheckDetailResponse from(CapCheckDetailLine line) {
        return new CapCheckDetailResponse(
                line.detailSeq(),
                line.itemName(),
                line.classification(),
                DisplayFormat.won(line.amount()),
                line.decisionReason(),
                line.evidenceRef()
        );
    }
}
