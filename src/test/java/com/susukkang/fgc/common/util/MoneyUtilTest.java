package com.susukkang.fgc.common.util;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MoneyUtilTest {

    @Test
    void 금액을_원_단위로_반올림한다() {
        assertThat(MoneyUtil.roundWon(new BigDecimal("100.5")))
                .isEqualByComparingTo("101");
        assertThat(MoneyUtil.roundWon(new BigDecimal("100.4")))
                .isEqualByComparingTo("100");
    }

    @Test
    void 금액과_비율을_곱한_뒤_원_단위로_반올림한다() {
        assertThat(MoneyUtil.multiplyAndRound(
                new BigDecimal("1000"),
                new BigDecimal("0.1255")
        )).isEqualByComparingTo("126");
    }

    @Test
    void 각_금액을_반올림한_뒤_합산한다() {
        assertThat(MoneyUtil.sumRounded(List.of(
                new BigDecimal("100.5"),
                new BigDecimal("200.4")
        ))).isEqualByComparingTo("301");
    }
}
