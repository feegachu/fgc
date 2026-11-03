package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.susukkang.fgc.compare.dto.CompareRankingFilters;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.dto.CompareRankingResponse.Item;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompareSummaryServiceTest {

    private static Item item(int rank, String insurer, String product, long net) {
        return new Item(rank, rank, insurer, product, net + 100_000, net + 50_000, 0, 50_000, net, 90.5, null, List.of());
    }

    private static CompareRankingResponse ranking() {
        return new CompareRankingResponse("r-1", "READY", "HEALTH_PROTECTION", "건강보험", LocalDate.of(2026, 7, 31), "TEST-RULE",
                "GA-CUR-2026-V1 v1", 100_000L, 12, List.of(), null,
                List.of(item(1, "가보험", "든든건강", 684_000), item(2, "나보험", "튼튼건강", 650_000)), List.of(),
                new CompareRankingFilters(List.of(1L), true));
    }

    @Test
    @DisplayName("LLM 에 보내는 데이터에는 순위·금액·경고만 있고 설계사·계약자 식별 정보가 없다")
    void describeHasOnlyRankingFacts() {
        String data = CompareSummaryService.describe(ranking());

        assertThat(data).contains("1위 가보험 든든건강", "684,000원", "2위 나보험 튼튼건강")
                .doesNotContain("agent", "계약번호", "주민");
    }

    @Test
    @DisplayName("적용한 세부 조건도 LLM 입력에 담는다")
    void describeIncludesAppliedFilters() {
        String data = CompareSummaryService.describe(ranking());

        assertThat(data).contains("보험사 1곳만 비교", "한도 초과 이월이 생기는 상품 제외");
    }

    @Test
    @DisplayName("세부 조건이 없으면 조건 줄을 만들지 않는다")
    void describeOmitsEmptyFilters() {
        CompareRankingResponse base = ranking();
        CompareRankingResponse none = new CompareRankingResponse(base.rankResultId(), base.status(), base.categoryCode(),
                base.categoryLabel(), base.asOf(), base.ruleVersion(), base.policyVersion(), base.basePremium(),
                base.clawbackMonth(), base.warnings(), base.capSummary(), base.items(), base.excludedItems(),
                CompareRankingFilters.NONE);

        assertThat(CompareSummaryService.describe(none)).doesNotContain("적용 조건");
    }

    @Test
    @DisplayName("세부 조건은 null 을 비우고 보험사 ID 를 중복 없이 정렬해 정규화한다")
    void filtersAreNormalized() {
        CompareRankingFilters normalized = new CompareRankingFilters(List.of(3L, 1L, 3L), null).normalized();

        assertThat(normalized.insurerIds()).containsExactly(1L, 3L);
        assertThat(normalized.capOverflowExcluded()).isFalse();
        assertThat(new CompareRankingFilters(null, null).normalized().isEmpty()).isTrue();
    }

    @Test
    @DisplayName("숫자 없이 1위 상품과 이유를 말로 쓴 요약은 통과한다")
    void acceptsSummaryWithoutNumbers() {
        String text = "가보험 든든건강이 환수 부담이 작아 가장 앞섰어요. 최종 선택은 설계사님이 하세요.";

        assertThat(CompareSummaryService.isValid(text, ranking())).isTrue();
    }

    @Test
    @DisplayName("숫자가 있으면 근거에 있는 값이어도 버린다 — 다른 항목·다른 상품의 값을 옮겨 쓸 수 있기 때문이다")
    void rejectsAnyNumber() {
        CompareRankingResponse ranking = ranking();

        assertThat(CompareSummaryService.isValid("가보험 든든건강이 1위이고 보수적 순수령액은 684,000원이에요.", ranking)).isFalse();
        assertThat(CompareSummaryService.isValid("가보험 든든건강의 보수적 순수령액은 50,000원이에요.", ranking)).isFalse();
        assertThat(CompareSummaryService.isValid("가보험 든든건강의 순수령액은 구백만원이에요.", ranking)).isFalse();
        assertThat(CompareSummaryService.isValid("가보험 든든건강의 순수령액은 ６８４,０００원이에요.", ranking)).isFalse();
    }

    @Test
    @DisplayName("단정 표현이나 1위 상품이 빠진 요약은 버린다")
    void rejectsAssertiveOrOffTopicText() {
        CompareRankingResponse ranking = ranking();

        assertThat(CompareSummaryService.isValid("가보험 든든건강이 반드시 가장 앞서요.", ranking)).isFalse();
        assertThat(CompareSummaryService.isValid("다른 상품을 보세요.", ranking)).isFalse();
        assertThat(CompareSummaryService.isValid("   ", ranking)).isFalse();
    }
}
