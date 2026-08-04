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
}