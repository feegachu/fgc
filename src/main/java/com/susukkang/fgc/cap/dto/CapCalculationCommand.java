package com.susukkang.fgc.cap.dto;

import com.susukkang.fgc.common.code.CapCheckKind;
import com.susukkang.fgc.common.code.PaymentStage;

import java.time.LocalDate;
import java.math.BigDecimal;

/**
 * 설명 : 한도 계산 요청과 증빙된 실제 준법경영비 입력
 * validationRunId는 REALTIME 계산에는 없고(null), MONTHLY/PRE_CONFIRM 배치 실행에서만 채워짐
 *
 * @author yslee
 * @since 2026-08-10
 * @version 1.2
 */
public record CapCalculationCommand(
        Long contractId,
        PaymentStage paymentStage,
        LocalDate asOfDate,
        CapCheckKind checkKind,
        Long validationRunId,
        BigDecimal complianceEvidenceAmount
) {
    public CapCalculationCommand(
            Long contractId,
            PaymentStage paymentStage,
            LocalDate asOfDate,
            CapCheckKind checkKind,
            Long validationRunId
    ) {
        this(contractId, paymentStage, asOfDate, checkKind, validationRunId, null);
    }

    public static CapCalculationCommand realtime(Long contractId, PaymentStage paymentStage, LocalDate asOfDate) {
        return realtime(contractId, paymentStage, asOfDate, null);
    }

    public static CapCalculationCommand realtime(
            Long contractId,
            PaymentStage paymentStage,
            LocalDate asOfDate,
            BigDecimal complianceEvidenceAmount
    ) {
        return new CapCalculationCommand(
                contractId,
                paymentStage,
                asOfDate,
                CapCheckKind.REALTIME,
                null,
                complianceEvidenceAmount
        );
    }
}
