package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class AnswerNumberGuardTest {

    private static final List<String> NAMES = List.of("가보험", "든든건강", "나보험", "건강보험 2026");

    @Test
    @DisplayName("숫자 없이 상품 이름과 이유만 쓴 문장은 통과한다")
    void proseWithoutNumbersIsAllowed() {
        assertThat(AnswerNumberGuard.allowed("가보험 든든건강이 환수 부담이 작아 가장 앞섰어요.", NAMES)).isTrue();
    }

    @ParameterizedTest
    @DisplayName("PR #739 리뷰의 우회 사례와 근거에 있는 숫자도 모두 거절한다 — 숫자는 서버 문장으로만 보여 준다")
    @ValueSource(strings = {
            "가보험 든든건강의 보수적 순수령액은 316,000원이에요.",
            "가보험 든든건강의 보수적 순수령액은 684,000원이에요.",
            "가보험 든든건강의 보수적 순수령액은 구백만원이에요.",
            "가보험 든든건강의 보수적 순수령액은 구백만 원이에요.",
            "가보험 든든건강의 보수적 순수령액은 ９９９９９９９원이에요.",
            "가보험 든든건강이 2위예요.",
            "환수율은 삼십 퍼센트예요.",
            // 항목 이름–값 결합 대조(21e578e1)로는 통과하던 경우: 항목 이름 없이 금액만 쓰거나 다른 상품의 값을 쓴다
            "가보험 든든건강은 316,000원을 받아요.",
            "나보험 튼튼건강의 보수적 순수령액은 684,000원이에요."})
    void numbersAreRejected(String text) {
        assertThat(AnswerNumberGuard.allowed(text, NAMES)).isFalse();
    }

    @Test
    @DisplayName("용어 이름 1,200%와 서버가 준 상품 이름 안의 숫자는 수치가 아니라서 허용한다")
    void termAndServerNamesAreNotNumbers() {
        assertThat(AnswerNumberGuard.allowed("1,200% 룰은 첫해 수수료가 한도를 넘지 않게 하는 규칙이에요.", NAMES)).isTrue();
        assertThat(AnswerNumberGuard.allowed("나보험 건강보험 2026이 환수 부담이 가장 작아요.", NAMES)).isTrue();
    }

    @Test
    @DisplayName("'이'·'만' 같은 조사 뒤에 '원'으로 시작하는 낱말이 와도 금액으로 보지 않는다")
    void particlesBeforeWonWordsAreNotAmounts() {
        assertThat(AnswerNumberGuard.allowed("환수율이 원수사 정책마다 달라요.", NAMES)).isTrue();
    }
}
