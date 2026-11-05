package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.susukkang.fgc.compare.dto.CompareRankingFilters;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.dto.CompareRankingResponse.Evidence;
import com.susukkang.fgc.compare.dto.CompareRankingResponse.Item;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CompareAskToolsTest {

    private static final String RANK_1 = "{\"rank\":1}";
    private static final String RANK_2 = "{\"rank\":2}";
    private static final String RANK_3 = "{\"rank\":3}";
    private static final String PAIR_2_3 = "{\"rankA\":2,\"rankB\":3}";
    private static final String PAIR_1_3 = "{\"rankA\":1,\"rankB\":3}";

    private static CompareRankingResponse ranking() {
        Evidence evidence = new Evidence("환수 노출액", "환수율 × 지급액", 50_000L, "환수 규칙", "P1", LocalDate.of(2026, 7, 31));
        Item first = new Item(1, 1, "가보험", "든든건강", 784_000, 734_000, 0, 50_000, 684_000, 90.5, null, List.of(evidence));
        Item second = new Item(2, 2, "나보험", "튼튼건강", 700_000, 650_000, 0, 16_000, 634_000, 80.0, null, List.of());
        Item third = new Item(3, 3, "다보험", "편한건강", 600_000, 580_000, 0, 30_000, 550_000, 70.0, null, List.of());
        return new CompareRankingResponse("r-1", "READY", "HEALTH_PROTECTION", "건강보험", LocalDate.of(2026, 7, 31), "TEST-RULE",
                "GA-CUR-2026-V1 v1", 100_000L, 12, List.of(), null, List.of(first, second, third), List.of(), CompareRankingFilters.NONE);
    }

    private static CompareAskTools toolsWith(String... nameAndArgs) {
        CompareAskTools tools = new CompareAskTools(ranking());
        for (int i = 0; i < nameAndArgs.length; i += 2) {
            tools.run(nameAndArgs[i], nameAndArgs[i + 1]);
        }
        return tools;
    }

    @Test
    @DisplayName("get_item 은 서버 값과 근거를 돌려주고, 없는 순위·잘못된 인자는 오류로 알려 준다")
    void getItemReturnsServerValues() {
        CompareAskTools tools = new CompareAskTools(ranking());

        assertThat(tools.run("get_item", RANK_1)).contains("684,000원", "50,000원", "734,000원", "환수율 × 지급액");
        assertThat(tools.run("get_item", "{\"rank\":9}")).contains("error");
        assertThat(tools.run("get_item", "not-json")).contains("error");
        assertThat(tools.run("drop_table", "{}")).contains("error");
    }

    @Test
    @DisplayName("compare 는 두 순위의 차이와 값이 더 큰 쪽을 서버가 정해 준다")
    void compareReturnsDifferenceAndBiggerSide() {
        String result = new CompareAskTools(ranking()).run("compare", PAIR_2_3);

        assertThat(result).contains("84,000원", "14,000원").contains("\"값이 더 큰 쪽\":\"2위\"").contains("\"값이 더 큰 쪽\":\"3위\"");
        assertThat(new CompareAskTools(ranking()).run("compare", "{\"rankA\":2,\"rankB\":2}")).contains("error");
    }

    @Test
    @DisplayName("explain_term 은 등록된 용어 설명만 돌려준다")
    void explainTermReturnsRegisteredTerms() {
        CompareAskTools tools = new CompareAskTools(ranking());

        assertThat(tools.run("explain_term", "{\"name\":\"이월\"}")).contains("다음으로 넘어가는");
        assertThat(tools.run("explain_term", "{\"name\":\"날씨\"}")).contains("error");
    }

    @Test
    @DisplayName("도구로 얻은 금액을 맞는 상품·항목과 함께 쓰면 통과한다")
    void valueWithMatchingItemAndMetricPasses() {
        CompareAskTools tools = toolsWith("get_item", RANK_1, "get_item", RANK_2);

        assertThat(tools.verify("1위 가보험 든든건강의 보수적 순수령액은 684,000원이에요. 2위의 환수 노출액은 16,000원이에요.")).isNull();
        assertThat(tools.verify("1,200% 룰은 첫해 수수료의 한도예요.")).isNull();
    }

    @Test
    @DisplayName("도구가 주지 않은 금액·한글 금액·순위 밖 번호·단위 없는 숫자는 거절한다")
    void unknownNumbersAreRejected() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.verify("1위의 보수적 순수령액은 999,000원이에요.")).isEqualTo("VALUE_NOT_FROM_TOOL");
        assertThat(tools.verify("1위의 보수적 순수령액은 육십팔만원이에요.")).isEqualTo("KOREAN_AMOUNT");
        assertThat(tools.verify("9위의 순수령액이 가장 커요.")).isEqualTo("UNKNOWN_RANK");
        assertThat(tools.verify("상품은 3개 있어요.")).isEqualTo("BARE_NUMBER");
    }

    @Test
    @DisplayName("다른 순위의 금액이나 항목이 다른 금액을 붙인 문장은 거절한다")
    void valueOnTheWrongItemOrMetricIsRejected() {
        CompareAskTools tools = toolsWith("get_item", RANK_2, "get_item", RANK_3);

        assertThat(tools.verify("3위 다보험 편한건강의 보수적 순수령액은 634,000원이에요.")).isEqualTo("VALUE_PAIR_MISMATCH");
        assertThat(tools.verify("2위 나보험 튼튼건강의 환수 노출액은 634,000원이에요.")).isEqualTo("VALUE_PAIR_MISMATCH");
        assertThat(tools.verify("634,000원이에요.")).isEqualTo("VALUE_PAIR_MISMATCH");
    }

    @Test
    @DisplayName("두 상품의 차이는 많고 적음이 서버 값과 같을 때만 통과한다")
    void directionMustMatchServerValues() {
        CompareAskTools tools = toolsWith("compare", PAIR_2_3);

        assertThat(tools.verify("3위 다보험 편한건강이 2위 나보험 튼튼건강보다 보수적 순수령액이 84,000원 적어요.")).isNull();
        assertThat(tools.verify("2위 나보험 튼튼건강이 3위 다보험 편한건강보다 보수적 순수령액이 84,000원 많아요.")).isNull();
        assertThat(tools.verify("3위 다보험 편한건강이 2위 나보험 튼튼건강보다 보수적 순수령액이 84,000원 많아요."))
                .isEqualTo("DIRECTION_MISMATCH");
        assertThat(tools.verify("2위 나보험 튼튼건강이 3위 다보험 편한건강보다 환수 노출액이 14,000원 적어요.")).isNull();
    }

    @Test
    @DisplayName("값이 같은 쌍은 방향을 어느 쪽으로 써도 막지 않는다")
    void equalValuesHaveNoDirection() {
        CompareAskTools tools = toolsWith("compare", PAIR_1_3);

        assertThat(tools.verify("1위 가보험 든든건강이 3위 다보험 편한건강보다 이월 예정액이 0원 많아요.")).isNull();
    }

    @Test
    @DisplayName("특정 상품을 권하는 표현은 거절하고, 추천하지 않는다는 안내는 막지 않는다")
    void recommendationsAreRejected() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.verify("1위 가보험 든든건강을 추천합니다.")).isEqualTo("RECOMMENDATION");
        assertThat(tools.verify("고객에게 가장 이득이 되는 상품은 1위예요.")).isEqualTo("RECOMMENDATION");
        assertThat(tools.verify("순위는 참고용이라 특정 상품을 추천하지는 않아요.")).isNull();
    }

    @Test
    @DisplayName("전각 숫자로 쓴 금액도 정규화해 도구 값과 대조한다")
    void fullWidthDigitsAreNormalized() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.verify("1위 가보험 든든건강의 보수적 순수령액은 ６８４,０００원이에요.")).isNull();
        assertThat(tools.verify("1위 가보험 든든건강의 보수적 순수령액은 ９９９,０００원이에요.")).isEqualTo("VALUE_NOT_FROM_TOOL");
    }
}
