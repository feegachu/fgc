package com.susukkang.fgc.reconciliation.domain;

/**
 * 설명 : 예상·실제 대사 결과 유형
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
public enum ReconciliationResultType {
    MATCHED,
    AMOUNT_DIFFERENCE,
    EXPECTED_MISSING,
    ACTUAL_MISSING,
    DUPLICATE,
    AGENT_MISMATCH,
    INSTALLMENT_MISMATCH,
    INVALID_CONTRACT_PAYMENT,
    POLICY_VERSION_ERROR,
    JOURNAL_IMBALANCE,
    REVIEW_REQUIRED
}
