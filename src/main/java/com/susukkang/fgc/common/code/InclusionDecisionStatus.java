package com.susukkang.fgc.common.code;

/**
 * 설명 : 1,200% 한도 산입 판단 상태
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public enum InclusionDecisionStatus {
    INCLUDED,
    EXCLUDED,
    REVIEW_REQUIRED;

    // 화면정의서에 이 컬럼(transaction_attribution.inclusion_status_snapshot) 전용 표기
    // 매핑표가 따로 없어서, 같은 문서의 "1,200% 판정" REVIEW_REQUIRED="검토필요"
    // (docs/FGC_화면정의서_v2_0.md:277)와 같은 용어를 그대로 맞췄다 — 확정 표기가 필요하면
    // 화면 담당자 확인 필요.
    public String label() {
        return switch (this) {
            case INCLUDED -> "포함";
            case EXCLUDED -> "제외";
            case REVIEW_REQUIRED -> "검토필요";
        };
    }
}
