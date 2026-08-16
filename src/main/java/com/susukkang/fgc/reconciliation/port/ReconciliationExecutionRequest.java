package com.susukkang.fgc.reconciliation.port;

import com.susukkang.fgc.common.code.PaymentStage;

import java.time.LocalDate;

/**
 * 설명 : 대사 실행기에 전달하는 실행 요청
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
public record ReconciliationExecutionRequest(
        Long reconciliationRunId,
        Long validationRunId,
        LocalDate settlementMonth,
        PaymentStage paymentStage,
        Long insurerId,
        Long requestedBy
) {
}
