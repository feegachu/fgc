package com.susukkang.fgc.reconciliation.dto;

import com.susukkang.fgc.reconciliation.domain.ReconciliationResultType;

import java.util.List;

/** 결정된 결과 유형과 주·보조 사유. */
public record ReconciliationClassification(
        ReconciliationResultType resultType,
        String primaryReasonCode,
        List<String> secondaryReasonCodes
) {
    public ReconciliationClassification {
        secondaryReasonCodes = List.copyOf(secondaryReasonCodes);
    }
}
