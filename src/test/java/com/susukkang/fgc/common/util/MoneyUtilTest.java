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
}
