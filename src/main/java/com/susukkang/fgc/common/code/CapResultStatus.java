package com.susukkang.fgc.common.code;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * cap_check.result_status 의 자바측 표현
 * DB CHECK (result_status IN ('NORMAL','WARNING','VIOLATION','REVIEW_REQUIRED','NOT_APPLICABLE')) 와 반드시 일치해야 함
 *
 * 판정 우선순위(위에서부터 순서대로 확인, 먼저 걸리는 조건이 최종 결과):
 * 1. REVIEW_REQUIRED — 룰셋 미분류 항목이나 환급률표 부재 등 사람 판단이 필요한 경우가 하나라도 있으면 최우선
 * 2. VIOLATION — 산입액이 한도를 실제로 초과
 * 3. WARNING — 아직 초과는 아니지만 1,200% 예산(산입 + 해약환급금 − 준법경영비 ÷ 연간 기준액)이
 *    경고 기준(cap_rule_set.warning_usage_pct, 기본 90%) 이상 — {@link #judge} 참조 (#490)
 * 4. NORMAL — 위 어디에도 해당하지 않는 정상 범위
 */
public enum CapResultStatus {
    NORMAL,
    WARNING,
    VIOLATION,
    REVIEW_REQUIRED,
    NOT_APPLICABLE;

    private static final BigDecimal ONE_HUNDRED = new BigDecimal("100");

    /**
     * 위반·주의·정상 판정. REVIEW_REQUIRED는 호출부가 먼저 거른다.
     * 계약 등록·월 검증(CapCalculatorImpl), 지급확정(CapValidatorImpl), 두 판정 결합(CommissionPaymentServiceImpl)이
     * 같은 규칙을 쓰도록 여기 한 곳에 둔다.
     *
     * 2026-10-12 yslee - 주의 판정을 1,200% 예산 기준으로 통일 (#490)
     * 기존 코드: 사용률(산입 ÷ 한도) ≥ warningUsagePct 이면 주의
     * 문제: 한도 = L − 해약환급금 + 준법경영비라 해약환급금 계약은 규정 예산 90%보다 0.1 × 해약환급금만큼 늦게 주의가 켜졌다
     * 개선: 규정 예산 (산입 + 해약환급금 − 준법경영비) ÷ L ≥ warningUsagePct 와 같은
     *       "잔여 ≤ (100 − warningUsagePct)% × L" 로 판정한다. 나눗셈 없이 비교해 반올림 경계 오차도 없다.
     *
     * @param remainingAmount 한도 − 산입 (음수면 위반)
     * @param annualizedLimitBase 연간 기준액 L = 월납환산 초회보험료 × 배수(기본 12)
     * @param warningUsagePct 주의 기준(%)
     */
    public static CapResultStatus judge(BigDecimal remainingAmount, BigDecimal annualizedLimitBase,
                                        BigDecimal warningUsagePct) {
        if (remainingAmount.signum() < 0) {
            return VIOLATION;
        }
        // L = 0이면 예산 비율이 정의되지 않는다 — 기존처럼 위반이 아니면 정상으로 둔다.
        if (annualizedLimitBase.signum() > 0
                && remainingAmount.multiply(ONE_HUNDRED)
                .compareTo(annualizedLimitBase.multiply(ONE_HUNDRED.subtract(warningUsagePct))) <= 0) {
            return WARNING;
        }
        return NORMAL;
    }

    /**
     * 게이지(사용률 = 산입 ÷ 한도)에서 {@link #judge}의 주의가 시작되는 사용률(%).
     * 100 − (100 − warningUsagePct) × L ÷ 한도. 해약환급금이 없으면 warningUsagePct 와 같다.
     * 한도가 0 이하거나 기준값이 없으면 null(표시선 숨김).
     */
    public static BigDecimal warningStartUsagePct(BigDecimal limitAmount, BigDecimal annualizedLimitBase,
                                                  BigDecimal warningUsagePct) {
        if (limitAmount == null || annualizedLimitBase == null || warningUsagePct == null
                || limitAmount.signum() <= 0) {
            return null;
        }
        return ONE_HUNDRED.subtract(ONE_HUNDRED.subtract(warningUsagePct)
                .multiply(annualizedLimitBase)
                .divide(limitAmount, 6, RoundingMode.HALF_UP));
    }

    /** SIR-008: 코드값은 항상 한글 라벨과 함께 응답한다 — 화면이 아니라 서버가 라벨을 만든다. */
    public String label() {
        return switch (this) {
            case NOT_APPLICABLE -> "적용대상 아님";
            case NORMAL -> "정상";
            case WARNING -> "주의";
            case VIOLATION -> "위반";
            case REVIEW_REQUIRED -> "검토필요";
        };
    }
}
