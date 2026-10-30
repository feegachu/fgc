package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnswerNumberGuardTest {
    private static final String FACTS = "순수령액 1,000,000원 | 환수 316,000원 | 한도 반영 수령 예상 1,316,000원 | 이월 0원 | 한도 영향 98.5%";

    @Test
    void 근거와_항목이_맞는_숫자는_통과한다() {
        assertThat(AnswerNumberGuard.allowed("순수령액은 1,000,000원이고 환수는 316,000원이에요.", FACTS)).isTrue();
    }

    @Test
    void 근거에_없는_숫자는_거절한다() {
        assertThat(AnswerNumberGuard.allowed("순수령액은 1,500,000원이에요.", FACTS)).isFalse();
    }

    @Test
    void 다른_항목의_값을_재사용하면_거절한다() {
        assertThat(AnswerNumberGuard.allowed("순수령액은 316,000원이에요.", FACTS)).isFalse();
    }

    @Test
    void 한글_숫자_금액은_거절한다() {
        assertThat(AnswerNumberGuard.allowed("순수령액은 구백만원이에요.", FACTS)).isFalse();
    }

    @Test
    void 전각_숫자는_정규화해서_대조한다() {
        assertThat(AnswerNumberGuard.allowed("순수령액은 ９９９９９９원이에요.", FACTS)).isFalse();
        assertThat(AnswerNumberGuard.allowed("환수는 ３１６,０００원이에요.", FACTS)).isTrue();
    }
}
