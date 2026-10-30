package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AnswerNumberGuardTest {
    private static final String FACTS = "순수령액 1,000,000원 | 환수 316,000원 | 한도 반영 수령 예상 1,316,000원 | 이월 0원 | 한도 영향 98.5%";
    private static final String OVER = "1위 A 상품 | 수령 예상 1,500,000원 | 한도 반영 1,200,000원 | 환수 노출 100,000원 | 보수적 순수령액 1,100,000원 | 한도 초과 이월 300,000원";
    private static final String COMPARE = "- 2위 B 상품: 1위보다 보수적 순수령액이 50,000원 적어요. 한도 반영 수령 예상액은 1위보다 20,000원 적고, 환수 노출액은 1위보다 30,000원 더 커요.";

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

    @Test
    void 한도_반영_전_금액을_한도_반영액이라_쓰면_거절한다() {
        assertThat(AnswerNumberGuard.allowed("한도 반영 수령 예상액은 1,500,000원이에요.", OVER)).isFalse();
        assertThat(AnswerNumberGuard.allowed("한도 반영 수령 예상액은 1,200,000원이에요.", OVER)).isTrue();
        assertThat(AnswerNumberGuard.allowed("수령 예상은 1,500,000원이에요.", OVER)).isTrue();
    }

    @Test
    void 비교_문장_안의_다른_차이값을_순수령액_차이로_쓰면_거절한다() {
        assertThat(AnswerNumberGuard.allowed("2위는 1위보다 보수적 순수령액이 30,000원 적어요.", COMPARE)).isFalse();
        assertThat(AnswerNumberGuard.allowed("2위는 1위보다 보수적 순수령액이 50,000원 적어요.", COMPARE)).isTrue();
        assertThat(AnswerNumberGuard.allowed("환수 노출액은 1위보다 30,000원 더 커요.", COMPARE)).isTrue();
    }
}
