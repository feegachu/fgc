package com.susukkang.fgc.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * SIR-008 표시 형식 표준(인터페이스정의서 2-4절) 변환 헬퍼.
 * 금액은 JSON 숫자(원 단위 정수), 요율·사용률은 JSON 문자열로 내려보낸다 — 화면에서 다시 반올림하지
 * 않도록 서버가 이미 확정된 형태로 준다.
 */
public final class DisplayFormat {

    private DisplayFormat() {
    }

    /** numeric(15,2) 등 금액 컬럼을 원 단위 정수로 변환한다. 저장 시 이미 HALF_UP 반올림된 값이라 안전하다. */
    public static long won(BigDecimal amount) {
        return amount.setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    /** usage_pct(12,6) 등 소수를 오차 없이 그대로 문자열로 넘긴다. */
    public static String rate(BigDecimal rate) {
        return rate == null ? null : rate.toPlainString();
    }
}
