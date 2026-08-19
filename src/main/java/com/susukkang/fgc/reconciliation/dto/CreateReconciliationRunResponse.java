package com.susukkang.fgc.reconciliation.dto;

/**
 * 설명 : 대사 실행 생성 응답
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
public record CreateReconciliationRunResponse(
        Long reconciliationRunId,
        String status
) {
    public static CreateReconciliationRunResponse from(ReconciliationRunRow row) {
        return new CreateReconciliationRunResponse(row.getReconciliationRunId(), row.getStatus());
    }
}
