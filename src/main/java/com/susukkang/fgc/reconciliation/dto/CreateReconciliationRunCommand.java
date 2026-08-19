package com.susukkang.fgc.reconciliation.dto;

import com.susukkang.fgc.common.code.PaymentStage;

import java.time.LocalDate;

/**
 * 설명 : 검증을 마친 대사 실행 생성 명령
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
public record CreateReconciliationRunCommand(
        LocalDate settlementMonth,
        PaymentStage paymentStage,
        Long insurerId,
        Long validationRunId,
        Long createdBy
) {
}
