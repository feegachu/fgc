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

    /** 계약 상세의 수동 한도 재검증용. 자동 REALTIME 이력과 구분해 저장한다. */
    public static CapCalculationCommand manual(
            Long contractId,
            PaymentStage paymentStage,
            LocalDate asOfDate,
            BigDecimal complianceEvidenceAmount
    ) {
        return new CapCalculationCommand(
                contractId,
                paymentStage,
                asOfDate,
                CapCheckKind.MANUAL,
                null,
                complianceEvidenceAmount
        );
    }

    /**
     * #76 DailyChangedContractJob 전용. cap_check.check_kind CHECK 제약에는 DAILY 값이 없어
     * checkKind는 REALTIME을 그대로 재사용하지만(스키마 변경 없이 안전 — uq_cap_check_monthly는
     * validation_run_id가 NULL인 행끼리만 서로 안 막으므로), validationRunId는 실제로 채워서
     * 넘긴다 — 그래야 (1) IF-BAT-02 §7-5 "그 밑에 결과를 붙인다" 대로 cap_check가 그날의
     * validation_run에 실제로 연결되고, (2) uq_cap_check_monthly(validation_run_id, contract_id,
     * payment_stage)가 "같은 실행 안에서 같은 계약·단계 중복 계산"을 실제로 막아준다(코드리뷰
     * 지적, 2026-08-11 — realtime()을 그대로 쓰면 validationRunId가 항상 null이라 이 보호가
     * 무력화됨).
     */
    public static CapCalculationCommand dailyBatch(Long contractId, PaymentStage paymentStage,
                                                     LocalDate asOfDate, Long validationRunId) {
        return new CapCalculationCommand(contractId, paymentStage, asOfDate, CapCheckKind.REALTIME, validationRunId);
    }
}
