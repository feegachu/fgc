package com.susukkang.fgc.common.code;

/**
 * product.protection_type 의 자바측 표현.
 * 1,200% 룰과 차익거래 규제는 보장성보험(PROTECTION)만 대상이다 — 일반손해·자동차보험과 저축성은 제외
 * (보험업감독규정 제4-32조⑤⑥, FAQ 2차 Q11). 상품군이 아니라 이 값으로 판정한다.
 */
public enum ProtectionType {
    PROTECTION,
    SAVINGS,
    GENERAL,
    AUTO,
    OTHER;

    /** 규제 판정(1,200%·차익거래) 대상인지. 보장성이 아니면 판정하지 않고 "적용대상 아님"으로 남긴다. */
    public static boolean isRegulated(String protectionType) {
        // 컬럼은 NOT NULL DEFAULT 'PROTECTION' 이라 null 은 값이 아직 채워지지 않은 조회 모델뿐이다.
        return protectionType == null || PROTECTION.name().equals(protectionType);
    }
}
