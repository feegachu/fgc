package com.susukkang.fgc.reconciliation.dto;

/** IF-API-42 불일치 예외 일괄 생성 응답. */
public record ReconciliationExceptionBulkCreateResponse(
        long created,
        long skippedDuplicate
) {
}
