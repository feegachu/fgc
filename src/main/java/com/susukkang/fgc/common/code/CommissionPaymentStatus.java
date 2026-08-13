package com.susukkang.fgc.common.code;

/**
 * 설명 : 수수료 지급 건 처리 상태
 *
 * @author yslee
 * @since 2026-08-05
 * @version 1.2
 */
public enum CommissionPaymentStatus {
    DRAFT,
    CONFIRMED,
    CANCELLED;

    /** 화면정의서 "지급 건 상태" 코드-표기 매핑표(docs/FGC_화면정의서_v2_0.md:250-256)와 일치시킨다. */
    public String label() {
        return switch (this) {
            case DRAFT -> "작성중";
            case CONFIRMED -> "확정";
            case CANCELLED -> "취소";
        };
    }
}
