package com.susukkang.fgc.journal.dto;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SonarQube javabugs:S2259 대응 — isBalanced()/differenceAmount()가 서비스 계층의
 * null 가드 없이 직접 호출돼도(예: 다른 호출부가 debitTotal/creditTotal 중 하나만
 * 채운 채 호출) NPE 없이 "미균형"으로 처리되는지 확인한다.
 */
class JournalBalanceSummaryTest {

    @Test
    void isBalancedIsFalseWithoutNpeWhenBothTotalsAreNull() {
        JournalBalanceSummary summary = new JournalBalanceSummary();

        assertThat(summary.isBalanced()).isFalse();
        assertThat(summary.differenceAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void isBalancedIsFalseWithoutNpeWhenOnlyOneTotalIsSet() {
        JournalBalanceSummary summary = new JournalBalanceSummary();
        summary.setDebitTotal(BigDecimal.valueOf(1000));

        assertThat(summary.isBalanced()).isFalse();
        assertThat(summary.differenceAmount()).isEqualByComparingTo(BigDecimal.valueOf(1000));
    }
}
