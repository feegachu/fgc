package com.susukkang.fgc.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyUtilTest {

    @Test
    void roundsAmountToNearestWon() {
        assertThat(MoneyUtil.roundWon(new BigDecimal("100.5")))
                .isEqualByComparingTo("101");
        assertThat(MoneyUtil.roundWon(new BigDecimal("100.4")))
                .isEqualByComparingTo("100");
    }

    @Test
    void multipliesAmountByRateAndRoundsToNearestWon() {
        assertThat(MoneyUtil.multiplyAndRound(
                new BigDecimal("1000"),
                new BigDecimal("0.1255")
        )).isEqualByComparingTo("126");
    }

    @Test
    void roundsEachAmountBeforeSumming() {
        assertThat(MoneyUtil.sumRounded(List.of(
                new BigDecimal("100.5"),
                new BigDecimal("200.4")
        ))).isEqualByComparingTo("301");
    }

    // 퍼센트를 곱한 뒤 원 단위로 반올림한다
    @Test
    void appliesPercentAndRoundsToNearestWon() {
        BigDecimal result = MoneyUtil.applyPercent(new BigDecimal("1200000"), new BigDecimal("24.000000"));
        assertThat(result).isEqualByComparingTo("288000");
    }

    // divide(...,10,HALF_UP) 로 중간 반올림을 한 번 거치면 0.49999999995(정확한 값은 0원)가
    // 0.5000000000 으로 반올림되어 최종 1원으로 잘못 올라간다 — 반올림은 정확히 한 번만 일어나야 한다
    @Test
    void appliesPercentWithoutDoubleRoundingAtHalfUpBoundary() {
        BigDecimal result = MoneyUtil.applyPercent(new BigDecimal("1"), new BigDecimal("49.999999995"));
        assertThat(result).isEqualByComparingTo("0");
    }

    // 분모가 0이면 0을 돌려준다
    @Test
    void returnsZeroUsagePercentWhenDenominatorIsZero() {
        assertThat(MoneyUtil.usagePercent(new BigDecimal("100"), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    // 소수 6자리 HALF_UP으로 계산한다
    @Test
    void calculatesUsagePercentWithSixDecimalsHalfUp() {
        BigDecimal result = MoneyUtil.usagePercent(new BigDecimal("1100000"), new BigDecimal("1200000"));
        assertThat(result).isEqualByComparingTo("91.666667");
    }
}
