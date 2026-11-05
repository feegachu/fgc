package com.susukkang.fgc.policy.code;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 설명 : FUN-062 계약체결비용 기준을 분급 정책버전 policy_parameter로 받는 키(운영정책서 제46조). 값은 규정 수치가 아니라
 * 회사가 기초서류 등에서 가져와 등록하는 입력이다. 그래서 기준율에는 값의 출처 설명을 함께 받는다.
 */
public final class AcquisitionCostParameter {
    public static final String BASIS = "ACQUISITION_COST_BASIS";
    public static final String RATE_PCT = "ACQUISITION_COST_RATE_PCT";
    /** 판단 코드가 구현한 기준금액. 다른 기준을 쓰려면 계산도 함께 고친다. */
    public static final String MONTHLY_EQUIVALENT_FIRST_PREMIUM = "MONTHLY_EQUIVALENT_FIRST_PREMIUM";

    private AcquisitionCostParameter() {
    }

    public static boolean accepts(String key, JsonNode value, String description) {
        return switch (key) {
            case BASIS -> value.isTextual() && MONTHLY_EQUIVALENT_FIRST_PREMIUM.equals(value.textValue());
            case RATE_PCT -> value.isNumber() && value.decimalValue().signum() > 0
                    && description != null && !description.isBlank();
            default -> false;
        };
    }
}
