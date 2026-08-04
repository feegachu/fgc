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

    @Test
    void applyPercent_은_퍼센트를_곱한_뒤_원단위로_반올림한다() {
        BigDecimal result = MoneyUtil.applyPercent(new BigDecimal("1200000"), new BigDecimal("24.000000"));
        assertThat(result).isEqualByComparingTo("288000");
    }

    @Test
    void usagePercent_은_분모가_0이면_0을_돌려준다() {
        assertThat(MoneyUtil.usagePercent(new BigDecimal("100"), BigDecimal.ZERO)).isEqualByComparingTo("0");
    }

    @Test
    void usagePercent_은_소수6자리_HALF_UP이다() {
        BigDecimal result = MoneyUtil.usagePercent(new BigDecimal("1100000"), new BigDecimal("1200000"));
        assertThat(result).isEqualByComparingTo("91.666667");
    }
}
