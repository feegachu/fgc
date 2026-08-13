package com.susukkang.fgc.common.code;

/**
 * reconciliation_result.result_type CHECK 제약과 값이 같아야 한다
 * (V1__baseline_v2_1_2.sql:1458-1462).
 */
public enum ReconResultType {
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
    REVIEW_REQUIRED;

    /** 화면정의서 "대사 결과" 코드-표기 매핑표(docs/FGC_화면정의서_v2_0.md:287-301)와 일치시킨다. */
    public String label() {
        return switch (this) {
            case MATCHED -> "일치";
            case AMOUNT_DIFFERENCE -> "금액 차이";
            case EXPECTED_MISSING -> "예상 없음(실제만 있음)";
            case ACTUAL_MISSING -> "실제 없음(누락)";
            case DUPLICATE -> "중복 지급";
            case AGENT_MISMATCH -> "설계사 불일치";
            case INSTALLMENT_MISMATCH -> "회차 불일치";
            case INVALID_CONTRACT_PAYMENT -> "비유효 계약 지급";
            case POLICY_VERSION_ERROR -> "정책버전 오류";
            case JOURNAL_IMBALANCE -> "원장 불균형";
            case REVIEW_REQUIRED -> "검토필요";
        };
    }
}
