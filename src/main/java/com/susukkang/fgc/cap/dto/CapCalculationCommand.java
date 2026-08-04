package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.PaymentStage;

import java.time.LocalDate;

/**
 * CapCalculator 호출 입력
 * validationRunId는 REALTIME 계산에는 없고(null), MONTHLY/PRE_CONFIRM 배치 실행에서만 채워짐
 */
public record CapCalculationCommand(
        Long contractId,
        PaymentStage paymentStage,
        LocalDate asOfDate,
        CapCheckKind checkKind,
        Long validationRunId
) {
    public static CapCalculationCommand realtime(Long contractId, PaymentStage paymentStage, LocalDate asOfDate) {
        return new CapCalculationCommand(contractId, paymentStage, asOfDate, CapCheckKind.REALTIME, null);
    }
}
