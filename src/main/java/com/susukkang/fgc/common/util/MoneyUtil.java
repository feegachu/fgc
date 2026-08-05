package com.susukkang.fgc.common.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Objects;

/**
 * FGC 금액 계산 공통 유틸리티.
 *
 * 각 지급행을 원 단위 HALF_UP으로 반올림한 뒤 합산한다.
 * 전체 금액을 먼저 합산하고 마지막에 반올림하면 안 된다.
 */
public final class MoneyUtil {

    private static final int WON_SCALE = 0;
    private static final RoundingMode ROUNDING_MODE =
            RoundingMode.HALF_UP;

    private MoneyUtil() {
    }

    public static BigDecimal roundWon(BigDecimal amount) {
        Objects.requireNonNull(amount, "amount는 null일 수 없습니다.");

        return amount.setScale(
                WON_SCALE,
                ROUNDING_MODE
        );
    }

    public static BigDecimal multiplyAndRound(
            BigDecimal baseAmount,
            BigDecimal rate
    ) {
        Objects.requireNonNull(
                baseAmount,
                "baseAmount는 null일 수 없습니다."
        );
        Objects.requireNonNull(
                rate,
                "rate는 null일 수 없습니다."
        );

        return roundWon(baseAmount.multiply(rate));
    }

    public static BigDecimal sumRounded(
            Collection<BigDecimal> amounts
    ) {
        Objects.requireNonNull(
                amounts,
                "amounts는 null일 수 없습니다."
        );

        return amounts.stream()
                .map(MoneyUtil::roundWon)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * amount 에 percent(%) 를 곱한 뒤 원 단위 HALF_UP 반올림한다. 예: percent=24.000000 → 24%.
     *
     * divide(..., 10, HALF_UP) 로 중간 반올림을 한 번 거친 뒤 roundWon 으로 다시 반올림하면, 두 번째
     * 반올림 경계에서 결과가 틀어질 수 있다(예: 정확한 값 0.49999999995 는 0원이어야 하는데 10자리로
     * 먼저 반올림하면 0.5000000000 이 되어 1원으로 잘못 올라간다). movePointLeft(2) 는 100으로 나누는
     * 것과 같지만 반올림 없이 소수점만 옮기므로, 최종 반올림이 정확히 한 번만 일어나게 만든다.
     */
    public static BigDecimal applyPercent(BigDecimal amount, BigDecimal percent) {
        Objects.requireNonNull(amount, "amount는 null일 수 없습니다.");
        Objects.requireNonNull(percent, "percent는 null일 수 없습니다.");

        return roundWon(amount.multiply(percent).movePointLeft(2));
    }

    /** numerator / denominator × 100 을 소수 6자리(HALF_UP)로 계산한다. cap_check.usage_pct(12,6) 저장용. */
    public static BigDecimal usagePercent(BigDecimal numerator, BigDecimal denominator) {
        Objects.requireNonNull(numerator, "numerator는 null일 수 없습니다.");
        Objects.requireNonNull(denominator, "denominator는 null일 수 없습니다.");

        if (denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.multiply(BigDecimal.valueOf(100))
                .divide(denominator, 6, RoundingMode.HALF_UP);
    }
}
