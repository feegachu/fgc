package com.susukkang.fgc.reconciliation.domain;

import com.susukkang.fgc.common.code.ReconResultType;

import java.util.Arrays;
import java.util.Optional;

/**
 * FUN-049-01 대사 불일치 사유와 우선순위를 한 곳에서 관리한다.
 */
public enum ReconciliationReasonCode {
    JOURNAL_IMBALANCE(10, "분개 불균형", ReconciliationResultType.JOURNAL_IMBALANCE),
    INVALID_CONTRACT_PAYMENT(20, "무효·취소 계약 지급", ReconciliationResultType.INVALID_CONTRACT_PAYMENT),
    POLICY_VERSION_ERROR(30, "정책 버전 오류", ReconciliationResultType.POLICY_VERSION_ERROR),
    DUPLICATE(40, "중복 지급", ReconciliationResultType.DUPLICATE),
    EXPECTED_MISSING(50, "예상 내역 없음", ReconciliationResultType.EXPECTED_MISSING),
    ACTUAL_MISSING(60, "실제 지급 없음", ReconciliationResultType.ACTUAL_MISSING),
    INSTALLMENT_MISMATCH(70, "회차 불일치", ReconciliationResultType.INSTALLMENT_MISMATCH),
    ORGANIZATION_MISMATCH(80, "소속 조직 불일치", ReconciliationResultType.AGENT_MISMATCH),
    AGENT_MISMATCH(90, "설계사 불일치", ReconciliationResultType.AGENT_MISMATCH),
    AMOUNT_DIFFERENCE(100, "금액 차이", ReconciliationResultType.AMOUNT_DIFFERENCE),
    REVIEW_REQUIRED(110, "검토 필요", ReconciliationResultType.REVIEW_REQUIRED),
    UNKNOWN(120, "분류 불가", ReconciliationResultType.REVIEW_REQUIRED),
    MATCHED(999, "일치", ReconciliationResultType.MATCHED);

    private final int priority;
    private final String label;
    private final ReconciliationResultType resultType;

    ReconciliationReasonCode(int priority, String label, ReconciliationResultType resultType) {
        this.priority = priority;
        this.label = label;
        this.resultType = resultType;
    }

    public int priority() {
        return priority;
    }

    public String label() {
        return label;
    }

    public ReconciliationResultType resultType() {
        return resultType;
    }

    public static Optional<ReconciliationReasonCode> fromCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return Arrays.stream(values()).filter(value -> value.name().equals(code)).findFirst();
    }

    public static String labelOf(String code) {
        return fromCode(code).map(ReconciliationReasonCode::label).orElse(code);
    }

    public static String resultTypeLabel(String code) {
        try {
            return ReconResultType.valueOf(code).label();
        } catch (IllegalArgumentException | NullPointerException exception) {
            return code;
        }
    }
}
