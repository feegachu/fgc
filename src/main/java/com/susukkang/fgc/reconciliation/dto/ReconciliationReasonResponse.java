package com.susukkang.fgc.reconciliation.dto;

import com.susukkang.fgc.reconciliation.domain.ReconciliationReasonCode;

/** 화면에 노출하는 분류 사유 코드와 라벨. */
public record ReconciliationReasonResponse(String code, String label) {
    public static ReconciliationReasonResponse from(String code) {
        String normalized = code == null || code.isBlank()
                ? ReconciliationReasonCode.UNKNOWN.name()
                : code;
        return new ReconciliationReasonResponse(normalized, ReconciliationReasonCode.labelOf(normalized));
    }
}
