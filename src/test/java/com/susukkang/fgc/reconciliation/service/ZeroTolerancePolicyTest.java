package com.susukkang.fgc.reconciliation.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

/**
 * 설명 : FGC-FUN-050-01/02 1차 허용오차 정책 경계 테스트
 *
 * @author yslee
 * @since 2026-08-14
 * @version 1.2
 */
class ZeroTolerancePolicyTest {

    private final TolerancePolicy policy = new ZeroTolerancePolicy();

    @Test
    void 금액은_소수점_표현과_무관하게_0원_차이만_일치한다() {
        assertThat(policy.matchesAmount(new BigDecimal("100.00"), new BigDecimal("100"))).isTrue();
        assertThat(policy.matchesAmount(new BigDecimal("100"), new BigDecimal("101"))).isFalse();
    }

    @Test
    void 날짜는_정확히_같은_날만_일치한다() {
        LocalDate expected = LocalDate.of(2026, 8, 15);

        assertThat(policy.matchesDate(expected, expected)).isTrue();
        assertThat(policy.matchesDate(expected, expected.plusDays(1))).isFalse();
    }

    @Test
    void 회차는_정확히_같은_회차만_일치한다() {
        assertThat(policy.matchesInstallment(13, 13)).isTrue();
        assertThat(policy.matchesInstallment(13, 14)).isFalse();
    }

    @Test
    void 비교_기준이_없는_null은_정책_호출_전에_검토대상으로_분리해야_한다() {
        assertThatNullPointerException().isThrownBy(() -> policy.matchesAmount(null, BigDecimal.ZERO));
        assertThatNullPointerException().isThrownBy(() -> policy.matchesDate(null, LocalDate.now()));
        assertThatNullPointerException().isThrownBy(() -> policy.matchesInstallment(null, 1));
    }
}
