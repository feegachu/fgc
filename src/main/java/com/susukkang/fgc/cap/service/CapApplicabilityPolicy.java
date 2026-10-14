package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.common.code.PaymentStage;
import java.time.LocalDate;
import java.util.Map;

/** #482: 시행 기준은 활성 룰셋 유무와 독립적이다. 기준 변경 시 버전도 갱신한다. */
public final class CapApplicabilityPolicy {
    public static final String BEFORE_EFFECTIVE_DATE = "CONTRACT_BEFORE_EFFECTIVE_DATE";
    public static final String BEFORE_EFFECTIVE_DATE_LABEL = "적용대상 아님 — 시행 전 계약";
    private CapApplicabilityPolicy() { }

    public static LocalDate effectiveContractDate(PaymentStage stage) {
        return switch (stage) {
            case INSURER_TO_GA -> LocalDate.of(2021, 1, 1);
            case GA_TO_FC -> LocalDate.of(2026, 7, 1);
        };
    }

    public static boolean isBeforeEffectiveDate(PaymentStage stage, LocalDate contractDate) {
        // 계약일 미확인은 비대상으로 우회하지 않는다. 필수값 검증/룰셋 오류를 유지한다.
        return contractDate != null && contractDate.isBefore(effectiveContractDate(stage));
    }

    public static Map<String, Object> snapshot(PaymentStage stage, LocalDate contractDate) {
        return Map.of("applicability", Map.of(
                "status", "NOT_APPLICABLE",
                "scope", "CONTRACT_PAYMENT_STAGE",
                "reasonCode", BEFORE_EFFECTIVE_DATE,
                "reason", BEFORE_EFFECTIVE_DATE_LABEL,
                "contractDate", contractDate.toString(),
                "paymentStage", stage.name(),
                "effectiveContractDate", effectiveContractDate(stage).toString(),
                "policyVersion", "CAP_APPLICABILITY_V1",
                "basisRefs", java.util.List.of("REG-08", "REG-09", "REG-19", "SRC-025 Q1-1")));
    }
}
