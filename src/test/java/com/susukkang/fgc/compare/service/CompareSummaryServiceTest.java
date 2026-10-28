package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.dto.CompareRankingResponse.Item;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompareSummaryServiceTest {

    private static Item item(int rank, String insurer, String product, long net) {
        return new Item(rank, rank, insurer, product, net + 100_000, net + 50_000, 0, 50_000, net, 24.0, 90.5, null, List.of());
    }

    private static CompareRankingResponse ranking() {
        return new CompareRankingResponse("r-1", "READY", "HEALTH_PROTECTION", "건강보험", LocalDate.of(2026, 7, 31), "TEST-RULE",
                "GA-CUR-2026-V1 v1", 100_000L, 12, List.of(), null,
                List.of(item(1, "가보험", "든든건강", 684_000), item(2, "나보험", "튼튼건강", 650_000)), List.of());
    }

    @Test
    @DisplayName("LLM 에 보내는 데이터에는 순위·금액·경고만 있고 설계사·계약자 식별 정보가 없다")
    void describeHasOnlyRankingFacts() {
        String data = CompareSummaryService.describe(ranking());

        assertThat(data).contains("1위 가보험 든든건강", "684,000원", "2위 나보험 튼튼건강");
        assertThat(data).doesNotContain("agent", "계약번호", "주민");
    }

    @Test
    @DisplayName("입력에 있는 숫자와 1위 상품명만 쓴 요약은 통과한다")
    void acceptsSummaryUsingOnlyGivenFacts() {
        CompareRankingResponse ranking = ranking();
        String data = CompareSummaryService.describe(ranking);

        String text = "가보험 든든건강이 1위이고 보수적 순수령액은 684,000원이에요. 최종 선택은 설계사님이 하세요.";

        assertThat(CompareSummaryService.isValid(text, data, ranking.items().get(0))).isTrue();
    }

    @Test
    @DisplayName("입력에 없는 숫자가 나오면 버린다")
    void rejectsInventedNumber() {
        CompareRankingResponse ranking = ranking();
        String data = CompareSummaryService.describe(ranking);

        String text = "가보험 든든건강이 1위이고 순수령액은 999,000원이에요.";

        assertThat(CompareSummaryService.isValid(text, data, ranking.items().get(0))).isFalse();
    }

    @Test
    @DisplayName("단정 표현이나 1위 상품이 빠진 요약은 버린다")
    void rejectsAssertiveOrOffTopicText() {
        CompareRankingResponse ranking = ranking();
        String data = CompareSummaryService.describe(ranking);

        assertThat(CompareSummaryService.isValid("가보험 든든건강이 반드시 1위예요.", data, ranking.items().get(0))).isFalse();
        assertThat(CompareSummaryService.isValid("다른 상품을 보세요.", data, ranking.items().get(0))).isFalse();
        assertThat(CompareSummaryService.isValid("   ", data, ranking.items().get(0))).isFalse();
    }
}
