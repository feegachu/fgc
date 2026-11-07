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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    @DisplayName("한도 영향은 화면처럼 소수 첫째 자리에서 절사해 모델에 준다(99.96% → 99.9%)")
    void capImpactIsTruncatedLikeTheScreen() {
        Item item = new Item(1, 1, "가보험", "든든건강", 784_000, 734_000, 0, 50_000, 684_000, 99.96, null, List.of());
        CompareRankingResponse base = ranking();
        CompareRankingResponse one = new CompareRankingResponse(base.rankResultId(), base.status(), base.categoryCode(),
                base.categoryLabel(), base.asOf(), base.ruleVersion(), base.policyVersion(), base.basePremium(), base.clawbackMonth(),
                base.warnings(), null, List.of(item), List.of(), CompareRankingFilters.NONE);

        assertThat(new CompareAskTools(one).run("get_item", RANK_1)).contains("99.9%").doesNotContain("100.0%");
        assertThat(CompareAskTools.percent(90.0)).isEqualTo("90.0%");
    }

    @Test
    @DisplayName("explain_term 은 등록된 용어 설명만 돌려준다")
    void explainTermReturnsRegisteredTerms() {
        CompareAskTools tools = new CompareAskTools(ranking());

        assertThat(tools.run("explain_term", "{\"name\":\"이월\"}")).contains("다음으로 넘어가는");
        assertThat(tools.run("explain_term", "{\"name\":\"날씨\"}")).contains("error");
    }

    @Test
    @DisplayName("숫자 없이 상품 이름과 이유를 말로 쓴 답은 통과하고, 용어 이름 1,200% 룰과 상품명 안의 숫자는 허용한다")
    void proseWithoutNumbersPasses() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.verify("가보험 든든건강은 환수 부담이 작아 보수적 순수령액이 가장 커서 앞섰어요.")).isNull();
        assertThat(tools.verify("1,200% 룰은 첫해 수수료의 한도예요.")).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "가보험 든든건강의 보수적 순수령액은 684,000원이에요.",  // 도구가 준 금액이어도 AI 문장에는 쓰지 않는다
            "9위 상품은 환수 부담이 작아요.",                      // 표시 범위 밖 순위 번호
            "가보험 든든건강의 한도 영향은 90.5%예요.",            // 비율
            "가보험 든든건강의 보수적 순수령액은 ６８４,０００원이에요.", // 전각 숫자
            "가보험 든든건강의 보수적 순수령액은 육십팔만원이에요.",     // 한글 금액
            "상품은 3개 있어요."})
    @DisplayName("AI 문장의 금액·비율·범위 밖 순위·전각·한글 금액은 도구 값이어도 거절한다")
    void numbersInAiTextAreRejected(String text) {
        assertThat(toolsWith("get_item", RANK_1).verify(text)).isEqualTo("NUMBER_GUARD");
    }

    @Test
    @DisplayName("표시 중인 범위의 순위 표기('1위')는 AI 문장에 써도 통과한다")
    void rankLabelsInsideTheShownRangeAreAllowed() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.verify("1위 가보험 든든건강은 환수 부담이 작아서 앞섰어요.")).isNull();
        assertThat(tools.verify("3위인 이유는 환수 노출액 때문이에요.")).isNull();
        assertThat(tools.verify("4위 상품은 없어요.")).isEqualTo("NUMBER_GUARD");
        assertThat(tools.verify("11위까지 있어요.")).isEqualTo("NUMBER_GUARD");
    }

    @Test
    @DisplayName("순위 표기로 쓴 비교 문장도 방향이 서버 값과 맞아야 한다")
    void rankLabelComparisonsKeepTheDirectionCheck() {
        CompareAskTools tools = toolsWith("compare", PAIR_2_3);

        assertThat(tools.verify("3위는 2위보다 보수적 순수령액이 적어요.")).isNull();
        assertThat(tools.verify("3위는 2위보다 보수적 순수령액이 많아요.")).isEqualTo("DIRECTION_MISMATCH");
        assertThat(toolsWith("get_item", RANK_2).verify("3위는 2위보다 보수적 순수령액이 적어요.")).isEqualTo("DIRECTION_NOT_FROM_TOOL");
    }

    @Test
    @DisplayName("'가장 크다/작다'는 상품 하나를 말하면 서버 값의 최댓값·최솟값과 맞아야 한다")
    void superlativeClaimsFollowTheServerValues() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.verify("가보험 든든건강이 보수적 순수령액이 가장 커요.")).isNull();
        assertThat(tools.verify("나보험 튼튼건강이 보수적 순수령액이 가장 커요.")).isEqualTo("SUPERLATIVE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강이 환수 노출액이 가장 작아요.")).isNull();
        assertThat(tools.verify("가보험 든든건강이 환수 노출액이 가장 작아요.")).isEqualTo("SUPERLATIVE_MISMATCH");
        // 상품이 둘 이상이거나 항목을 알 수 없으면 판단하지 않는다.
        assertThat(tools.verify("가보험 든든건강과 나보험 튼튼건강이 가장 커요.")).isNull();
        assertThat(tools.verify("보수적 순수령액이 가장 큰 순서로 정해졌어요.")).isNull();
    }

    @Test
    @DisplayName("이월이 없으면 '한도에 걸렸다'는 말을 거절하고, 걸리지 않았다는 말은 통과한다")
    void capStateClaimsFollowTheCarryover() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.verify("가보험 든든건강은 한도에 걸려 있어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("세 상품 모두 한도를 초과하지 않았어요.")).isNull();
        // 조사 없는 '한도 초과 시'나 조건 설명은 상태 주장이 아니다.
        assertThat(tools.verify("한도 초과 시 이월 예정액이 생겨요.")).isNull();
        assertThat(tools.verify("한도를 초과하면 이월돼요.")).isNull();
        // 용어 설명의 관계절은 상태 주장이 아니다(실측에서 오탐).
        assertThat(tools.verify("이 한도를 넘는 금액은 이월 예정액으로 따로 계산돼요.")).isNull();
    }

    @Test
    @DisplayName("순위 표기와 상품 이름이 같은 문장에서 서로 다른 상품을 가리키면 거절한다")
    void rankLabelAndNameMustPointAtTheSameProduct() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.verify("1위는 나보험 튼튼건강이에요. 환수 부담이 작아서 앞섰어요.")).isEqualTo("RANK_MISMATCH");
        assertThat(tools.verify("1위는 가보험 든든건강이에요. 환수 부담이 작아서 앞섰어요.")).isNull();
        // 한쪽이 다른 쪽에 포함되면 어긋난 말이 아니다.
        assertThat(tools.verify("1위는 가보험 든든건강이고 나보험 튼튼건강과 함께 비교했어요.")).isNull();
        assertThat(tools.verify("1위 가보험 든든건강과 3위 편한건강은 환수 부담이 달라요.")).isNull();
        assertThat(tools.verify("1위와 2위는 나보험 튼튼건강과 다보험 편한건강이에요.")).isEqualTo("RANK_MISMATCH");
        // 순위를 맞바꾸거나, 포함 관계여도 1위를 다른 상품이라고 한 문장은 짝이 틀린 말이다.
        assertThat(tools.verify("1위는 나보험 튼튼건강이고 2위는 가보험 든든건강이에요.")).isEqualTo("RANK_MISMATCH");
        assertThat(tools.verify("1위는 나보험 튼튼건강이고 가보험 든든건강과 비교했어요.")).isEqualTo("RANK_MISMATCH");
        assertThat(tools.verify("1위는 가보험 든든건강이고 2위는 나보험 튼튼건강이에요.")).isNull();
        assertThat(tools.verify("1위와 2위는 가보험 든든건강과 나보험 튼튼건강이에요.")).isNull();
        // 이름이 순위 표기 앞에 오는 꼴도 짝을 본다(#911 리뷰).
        assertThat(tools.verify("나보험 튼튼건강이 1위예요.")).isEqualTo("RANK_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 1위, 가보험 든든건강은 2위예요.")).isEqualTo("RANK_MISMATCH");
        assertThat(tools.verify("가보험 든든건강은 1위, 나보험 튼튼건강은 2위예요.")).isNull();
        assertThat(tools.verify("든든건강이 1위이고 튼튼건강이 2위예요.")).isNull();
        assertThat(tools.verify("든든건강이 1위이고 튼튼건강은 환수 부담이 더 작아요.")).isNull();
        assertThat(tools.verify("가보험 든든건강이 1위예요.")).isNull();
        // 표기와 이름 사이에 말이 낀 꼴도 같은 절의 첫 이름과 짝을 본다(#911 리뷰).
        assertThat(tools.verify("1위 상품은 나보험 튼튼건강이에요.")).isEqualTo("RANK_MISMATCH");
        assertThat(tools.verify("1위는 바로 나보험 튼튼건강이에요.")).isEqualTo("RANK_MISMATCH");
        assertThat(tools.verify("1위를 차지한 상품은 나보험 튼튼건강이에요.")).isEqualTo("RANK_MISMATCH");
        assertThat(tools.verify("1위 상품은 가보험 든든건강이에요.")).isNull();
        assertThat(tools.verify("1위는 바로 가보험 든든건강이에요.")).isNull();
        // 접속 표현이 끼거나 비교 상대로 쓰인 이름은 짝이 아니다.
        assertThat(tools.verify("1위는 환수 부담이 작지만 나보험 튼튼건강은 보수적 순수령액이 작아요.")).isNull();
        assertThat(tools.verify("1위는 환수 부담이 작아서 나보험 튼튼건강보다 앞서요.")).isNull();
        // 연결 어미(인데·이므로·이라·이나)로 이어진 문장도 앞 이름과 짝을 본다(#911 리뷰).
        assertThat(tools.verify("가보험 든든건강이 1위인데 나보험 튼튼건강은 2위예요.")).isNull();
        assertThat(tools.verify("가보험 든든건강이 1위이므로 나보험 튼튼건강은 2위예요.")).isNull();
        assertThat(tools.verify("가보험 든든건강이 1위이나 나보험 튼튼건강은 환수 부담이 더 커요.")).isNull();
        assertThat(tools.verify("나보험 튼튼건강이 1위인데 가보험 든든건강은 2위예요.")).isEqualTo("RANK_MISMATCH");
        // 이름이 묶음보다 적으면 판단하지 않는다.
        assertThat(tools.verify("1위보다 2위가 나보험 튼튼건강이에요.")).isNull();
    }

    @Test
    @DisplayName("'한도에 걸렸다'는 말한 상품 모두에 이월이 있어야 맞고, 섞인 주장은 판단하지 않는다")
    void capStateClaimsNeedEveryMentionedProductToCarryOver() {
        CompareRankingResponse base = ranking();
        Item first = new Item(1, 1, "가보험", "든든건강", 784_000, 734_000, 0, 50_000, 684_000, 90.5, null, List.of());
        Item second = new Item(2, 2, "나보험", "튼튼건강", 700_000, 650_000, 100_000, 16_000, 634_000, 80.0, null, List.of());
        Item third = new Item(3, 3, "다보험", "편한건강", 600_000, 580_000, 0, 30_000, 550_000, 70.0, null, List.of());
        CompareAskTools tools = new CompareAskTools(new CompareRankingResponse(base.rankResultId(), base.status(), base.categoryCode(),
                base.categoryLabel(), base.asOf(), base.ruleVersion(), base.policyVersion(), base.basePremium(), base.clawbackMonth(),
                base.warnings(), null, List.of(first, second, third), List.of(), CompareRankingFilters.NONE));
        tools.run("get_item", RANK_1);

        assertThat(tools.verify("모든 상품이 한도에 걸렸어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("가보험 든든건강과 나보험 튼튼건강 모두 한도에 걸렸어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도에 걸렸어요.")).isNull();
        assertThat(tools.verify("모든 상품이 한도를 초과하지 않았어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("가보험 든든건강은 한도를 초과하지 않았어요.")).isNull();
        // 같은 주장을 반복한 문장은 함께 검사하고, 서로 다른 주장이 한 문장에 섞이면 판단하지 않는다.
        assertThat(tools.verify("가보험 든든건강은 한도에 걸렸고 초과했어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도에 걸렸고 가보험 든든건강은 걸리지 않았어요.")).isNull();
        // 도달 표현과 무관한 뒤쪽 서술어의 '못'·'없'은 부정이 아니다(#919).
        assertThat(tools.verify("나보험 튼튼건강은 한도를 넘어 받지 못한 금액이 있어요.")).isNull();
        // 부정어가 바로 뒤가 아니면 긍정인지 부정인지 알 수 없어 거절한다(안전한 쪽). 못 받은 금액이 '있다'는 말만 긍정으로 읽는다.
        assertThat(tools.verify("나보험 튼튼건강은 한도에 걸려 받지 못하고 다음 달로 넘어가요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도에 걸리지 않았어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도에 걸리지는 않았어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도를 초과한 적이 없어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("가보험 든든건강은 한도를 초과하지 못했어요.")).isNull();
        // 긍정과 부정을 정규식으로 가를 수 없는 꼴('~해 … 금액이 없어요')은 긍정으로 읽는다. 이월이 없는 상품이면 거절(안전한 쪽)된다.
        assertThat(tools.verify("가보험 든든건강은 한도를 초과해 이번 달에 더 받을 수 있는 금액이 없어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("가보험 든든건강은 한도를 넘어 이번 달 지급분이 없어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("가보험 든든건강은 한도에 도달해 이번 달 추가 수수료가 없어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도를 초과해 이번 달에 더 받을 수 있는 금액이 없어요.")).isEqualTo("CAP_STATE_MISMATCH");
        // 허용 목록 밖의 활용형 부정도 틀린 주장을 통과시키지 않는다(#925 리뷰). 이월이 있는 상품에 대해 '안 걸렸다'고 하면 거절한다.
        assertThat(tools.verify("나보험 튼튼건강은 한도를 넘기지 않았어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도를 넘긴 적이 없어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도를 초과된 적이 없어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도를 넘어선 적이 없어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도를 초과할 일이 없어요.")).isEqualTo("CAP_STATE_MISMATCH");
        // 못 받은 금액이 있다는 말이 뒤에 붙어도 앞쪽의 다른 부정을 덮지 않는다.
        assertThat(tools.verify("나보험 튼튼건강은 한도를 넘긴 적은 없지만 아직 받지 못한 금액이 있어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도를 넘기지 않았고 받지 못한 금액이 있어요.")).isEqualTo("CAP_STATE_MISMATCH");
        // 활용이 낀 부정도 부정이다(#925 리뷰).
        assertThat(tools.verify("가보험 든든건강은 한도를 넘어서지 않았어요.")).isNull();
        assertThat(tools.verify("가보험 든든건강은 한도에 걸려 있지 않아요.")).isNull();
        assertThat(tools.verify("가보험 든든건강은 한도에 걸리지도 않았어요.")).isNull();
        assertThat(tools.verify("가보험 든든건강은 한도에 걸린 상태가 아니에요.")).isNull();
        assertThat(tools.verify("나보험 튼튼건강은 한도를 넘어서지 않았어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도에 걸려 있지 않아요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도에 걸리지도 않았어요.")).isEqualTo("CAP_STATE_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강은 한도에 걸린 상태가 아니에요.")).isEqualTo("CAP_STATE_MISMATCH");
    }

    @Test
    @DisplayName("금액·비율이 든 문장만 빼고 나머지 설명을 남긴다")
    void onlyNumberSentencesAreDropped() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.keepNumberFreeSentences("1위는 든든건강이에요. 보수적 순수령액은 684,000원이에요. 환수가 작아서 앞섰어요."))
                .isEqualTo("1위는 든든건강이에요. 환수가 작아서 앞섰어요.");
        assertThat(tools.keepNumberFreeSentences("순수령액은 684,000원이에요. 한도 영향은 90.5%예요.")).isEmpty();
        assertThat(tools.keepNumberFreeSentences("환수가 작아서 앞섰어요.")).isEqualTo("환수가 작아서 앞섰어요.");
        assertThat(tools.keepNumberFreeSentences("9위 상품은 없어요. 환수가 작아요.")).isEqualTo("환수가 작아요.");
    }

    @Test
    @DisplayName("항목 없이 가장 앞섰다고만 쓴 문장은 1위 상품에만 맞다")
    void leadClaimMustPointAtTheTopProduct() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.verify("나보험 튼튼건강이 가장 앞섰어요.")).isEqualTo("LEAD_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강이 제일 먼저 앞섰어요.")).isEqualTo("LEAD_MISMATCH");
        assertThat(tools.verify("가보험 든든건강이 제일 먼저 앞섰어요.")).isNull();
        // 부정형은 뜻이 뒤집힌다: 1위가 가장 앞서지 않았다는 말이 틀리다.
        assertThat(tools.verify("가보험 든든건강이 가장 앞서지 않았어요.")).isEqualTo("LEAD_MISMATCH");
        assertThat(tools.verify("가보험 든든건강은 가장 앞선 상품이 아니에요.")).isEqualTo("LEAD_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강이 가장 앞서지 않았어요.")).isNull();
        assertThat(tools.verify("나보험 튼튼건강은 가장 앞선 상품이 아니에요.")).isNull();
        assertThat(tools.verify("가보험 든든건강이 가장 앞섰어요.")).isNull();
        assertThat(tools.verify("1위 가보험 든든건강이 가장 앞서요.")).isNull();
        // 가정이거나 둘 이상을 견주는 문장, 항목을 붙인 문장은 판단하지 않는다.
        assertThat(tools.verify("나보험 튼튼건강이 가장 앞선다면 좋겠지만 순위는 참고용이에요.")).isNull();
        assertThat(tools.verify("가보험 든든건강이 나보험 튼튼건강보다 가장 앞섰어요.")).isNull();
    }

    @Test
    @DisplayName("서버 내부 도구를 언급하는 답은 거절한다")
    void internalToolMentionsAreRejected() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.verify("이 챗봇은 도구를 통해 비교 값을 제공하며 순위를 설명해요.")).isEqualTo("TOOL_MENTION");
        assertThat(tools.verify("get_item 으로 값을 확인했어요.")).isEqualTo("TOOL_MENTION");
        assertThat(tools.verify("내부 도구로 값을 확인했어요.")).isEqualTo("TOOL_MENTION");
        // 일반 용례의 '도구'는 막지 않는다.
        assertThat(tools.verify("이 챗봇은 상품 비교를 돕는 보조 도구예요.")).isNull();
        assertThat(tools.verify("compare(1, 2) 결과를 보면 앞서요.")).isEqualTo("TOOL_MENTION");
        assertThat(tools.verify("이 챗봇은 비교 결과와 기본 용어를 안내해요.")).isNull();
    }

    @Test
    @DisplayName("특정 상품을 권하는 표현은 거절하고, 추천하지 않는다는 안내는 막지 않는다")
    void recommendationsAreRejected() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        assertThat(tools.verify("가보험 든든건강을 추천합니다.")).isEqualTo("RECOMMENDATION");
        assertThat(tools.verify("고객에게 가장 이득이 되는 상품은 가보험 든든건강이에요.")).isEqualTo("RECOMMENDATION");
        assertThat(tools.verify("순위는 참고용이라 특정 상품을 추천하지는 않아요.")).isNull();
    }

    @Test
    @DisplayName("같은 뜻의 추천 표현 변형도 거절하고, 추천하지 않는다는 안내는 통과시킨다")
    void recommendationVariantsAreRejectedButRefusalsPass() {
        CompareAskTools tools = toolsWith("get_item", RANK_1);

        for (String text : List.of("가보험 든든건강을 추천드려요.", "든든건강을 추천할게요.", "든든건강을 추천해 드릴게요.",
                "든든건강이 더 낫습니다.", "든든건강이 좋은 선택이에요.")) {
            assertThat(tools.verify(text)).as(text).isEqualTo("RECOMMENDATION");
        }
        assertThat(tools.verify("특정 상품을 추천드리지 않아요.")).isNull();
        assertThat(tools.verify("특정 상품을 추천해 드리기는 어려워요.")).isNull();
    }

    @Test
    @DisplayName("두 상품의 많고 적음은 compare 결과의 방향과 맞아야 하고, 'B보다 A가' 어순도 본다")
    void comparisonMustMatchToolDirection() {
        // 환수 노출액: 튼튼건강(2위) 16,000원, 편한건강(3위) 30,000원 → 튼튼건강이 더 작다
        CompareAskTools compared = toolsWith("compare", PAIR_2_3);

        assertThat(compared.verify("나보험 튼튼건강이 다보험 편한건강보다 환수 부담이 더 작아요.")).isNull();
        assertThat(compared.verify("다보험 편한건강보다 나보험 튼튼건강이 환수 부담이 더 작아요.")).isNull();
        assertThat(compared.verify("다보험 편한건강이 나보험 튼튼건강보다 환수 부담이 더 작아요.")).isEqualTo("DIRECTION_MISMATCH");
        assertThat(compared.verify("나보험 튼튼건강보다 다보험 편한건강이 환수 부담이 더 작아요.")).isEqualTo("DIRECTION_MISMATCH");
        // '보수적'의 '적'은 서술어로 읽지 않는다.
        assertThat(compared.verify("나보험 튼튼건강이 다보험 편한건강보다 보수적 순수령액이 더 많아요.")).isNull();
    }

    @Test
    @DisplayName("값이 같은 항목에 많다·적다고 쓰면 거절한다")
    void equalValuesHaveNoDirection() {
        // 이월 예정액은 2위·3위 모두 0원
        CompareAskTools compared = toolsWith("compare", PAIR_2_3);

        assertThat(compared.verify("나보험 튼튼건강이 다보험 편한건강보다 이월 예정액이 더 많아요.")).isEqualTo("DIRECTION_MISMATCH");
        assertThat(compared.verify("다보험 편한건강이 나보험 튼튼건강보다 이월 예정액이 더 적어요.")).isEqualTo("DIRECTION_MISMATCH");
    }

    @Test
    @DisplayName("compare 로 견주지 않은 두 상품이나 compare 가 주지 않는 항목의 많고 적음은 근거 없는 주장으로 거절한다")
    void comparisonWithoutToolEvidenceIsRejected() {
        String claim = "나보험 튼튼건강이 다보험 편한건강보다 환수 부담이 더 작아요.";

        assertThat(toolsWith("get_item", RANK_2, "get_item", RANK_3).verify(claim)).isEqualTo("DIRECTION_NOT_FROM_TOOL");
        // compare(1,3)을 불렀어도 2위·3위를 견준 근거는 아니다.
        assertThat(toolsWith("compare", PAIR_1_3).verify(claim)).isEqualTo("DIRECTION_NOT_FROM_TOOL");
        // 한도 영향은 compare 가 차이를 주지 않는다.
        assertThat(toolsWith("compare", PAIR_2_3).verify("나보험 튼튼건강이 다보험 편한건강보다 한도 영향이 더 커요."))
                .isEqualTo("DIRECTION_NOT_FROM_TOOL");
    }

    @Test
    @DisplayName("상품 하나만 말하거나 항목 이름이 없는 문장은 방향을 확인할 수 없어 보지 않는다")
    void sentencesWithoutTwoProductsAndMetricAreNotChecked() {
        CompareAskTools tools = toolsWith("get_item", RANK_2);

        assertThat(tools.verify("나보험 튼튼건강은 환수 부담이 작은 편이에요.")).isNull();
        assertThat(tools.verify("나보험 튼튼건강이 다보험 편한건강보다 앞섰어요.")).isNull();
    }

    /** 가보험이 상품 둘(1위 든든건강, 3위 편한건강)을 낸 결과. 환수 노출액: 1위 50,000원, 2위 16,000원. */
    private static CompareAskTools sharedInsurerTools(String... nameAndArgs) {
        CompareRankingResponse base = ranking();
        Item first = new Item(1, 1, "가보험", "든든건강", 784_000, 734_000, 0, 50_000, 684_000, 90.5, null, List.of());
        Item second = new Item(2, 2, "나보험", "튼튼건강", 700_000, 650_000, 0, 16_000, 634_000, 80.0, null, List.of());
        Item third = new Item(3, 3, "가보험", "편한건강", 600_000, 580_000, 0, 30_000, 550_000, 70.0, null, List.of());
        CompareAskTools tools = new CompareAskTools(new CompareRankingResponse(base.rankResultId(), base.status(), base.categoryCode(),
                base.categoryLabel(), base.asOf(), base.ruleVersion(), base.policyVersion(), base.basePremium(), base.clawbackMonth(),
                base.warnings(), null, List.of(first, second, third), List.of(), CompareRankingFilters.NONE));
        for (int i = 0; i < nameAndArgs.length; i += 2) {
            tools.run(nameAndArgs[i], nameAndArgs[i + 1]);
        }
        return tools;
    }

    @Test
    @DisplayName("보험사가 상품을 둘 이상 내면 보험사 이름만으로는 상품을 가리키지 않아, 틀린 방향의 비교 문장도 검사된다")
    void sharedInsurerNameDoesNotPointToEveryProduct() {
        CompareAskTools tools = sharedInsurerTools("compare", "{\"rankA\":1,\"rankB\":2}");

        assertThat(tools.ranksMentioned("가보험 든든건강이 나보험 튼튼건강보다 환수 노출액이 더 작아요.")).containsExactlyInAnyOrder(1, 2);
        assertThat(tools.verify("가보험 든든건강이 나보험 튼튼건강보다 환수 노출액이 더 작아요.")).isEqualTo("DIRECTION_MISMATCH");
        assertThat(tools.verify("나보험 튼튼건강이 가보험 든든건강보다 환수 노출액이 더 작아요.")).isNull();
        assertThat(tools.verify("가보험 든든건강보다 나보험 튼튼건강이 환수 노출액이 더 작아요.")).isNull();
        // 상품이 둘인 보험사 이름만으로는 어느 상품인지 모른다. 상품이 하나뿐인 보험사 이름은 그 상품이다.
        assertThat(tools.ranksMentioned("가보험 상품이 앞섰어요.")).isEmpty();
        assertThat(tools.ranksMentioned("나보험 상품이 뒤따랐어요.")).containsExactly(2);
    }

    @Test
    @DisplayName("보험사만 다르고 이름이 같은 상품은 '보험사 상품' 꼴로 붙여 써야 그 상품으로 본다")
    void sameProductNameNeedsInsurer() {
        CompareRankingResponse base = ranking();
        Item first = new Item(1, 1, "가보험", "튼튼건강", 784_000, 734_000, 0, 50_000, 684_000, 90.5, null, List.of());
        Item second = new Item(2, 2, "나보험", "튼튼건강", 700_000, 650_000, 0, 16_000, 634_000, 80.0, null, List.of());
        CompareAskTools tools = new CompareAskTools(new CompareRankingResponse(base.rankResultId(), base.status(), base.categoryCode(),
                base.categoryLabel(), base.asOf(), base.ruleVersion(), base.policyVersion(), base.basePremium(), base.clawbackMonth(),
                base.warnings(), null, List.of(first, second), List.of(), CompareRankingFilters.NONE));

        assertThat(tools.ranksMentioned("튼튼건강은 환수 부담이 작아요.")).isEmpty();
        assertThat(tools.ranksMentioned("나보험 튼튼건강은 환수 부담이 작아요.")).containsExactly(2);
    }

    @Test
    @DisplayName("답에서 언급한 상품은 보험사·상품 이름으로 찾는다")
    void ranksMentionedByName() {
        CompareAskTools tools = new CompareAskTools(ranking());

        assertThat(tools.ranksMentioned("나보험 튼튼건강은 편한건강보다 환수 부담이 작아요.")).containsExactlyInAnyOrder(2, 3);
        assertThat(tools.ranksMentioned("가보험 상품이 앞섰어요.")).containsExactly(1);
    }

    /** 시드 형태 이름. 환수 노출액: 든든(1위) 50,000원, 장기(2위) 16,000원 → 장기 건강보험이 더 작다. */
    static CompareRankingResponse seedNamedRanking() {
        CompareRankingResponse base = ranking();
        return new CompareRankingResponse(base.rankResultId(), base.status(), base.categoryCode(), base.categoryLabel(), base.asOf(),
                base.ruleVersion(), base.policyVersion(), base.basePremium(), base.clawbackMonth(), base.warnings(), null, List.of(
                        new Item(1, 1, "가보험", "가상 든든 건강보험 C", 784_000, 734_000, 0, 50_000, 684_000, 90.5, null, List.of()),
                        new Item(2, 2, "나보험", "가상 장기 건강보험 E", 700_000, 650_000, 0, 16_000, 634_000, 80.0, null, List.of()),
                        new Item(3, 3, "다보험", "가상 튼튼 건강보험 F", 600_000, 580_000, 0, 30_000, 550_000, 70.0, null, List.of())),
                List.of(), CompareRankingFilters.NONE);
    }

    @Test
    @DisplayName("질문을 따라 줄여 쓴 상품 이름('든든 건강보험')도 질문과 같은 규칙으로 찾아 방향을 검사한다")
    void shortenedSeedNamesInAnswerAreChecked() {
        CompareAskTools compared = new CompareAskTools(seedNamedRanking());
        compared.run("compare", "{\"rankA\":1,\"rankB\":2}");

        assertThat(compared.ranksMentioned("든든 건강보험이 장기 건강보험보다 환수 부담이 더 작아요.")).containsExactlyInAnyOrder(1, 2);
        assertThat(compared.verify("든든 건강보험이 장기 건강보험보다 환수 부담이 더 작아요.")).isEqualTo("DIRECTION_MISMATCH");
        assertThat(compared.verify("장기 건강보험이 든든 건강보험보다 환수 부담이 더 작아요.")).isNull();
        assertThat(compared.verify("든든건강보험보다 장기건강보험이 환수 부담이 더 작아요.")).isNull();

        CompareAskTools notCompared = new CompareAskTools(seedNamedRanking());
        assertThat(notCompared.verify("장기 건강보험이 든든 건강보험보다 환수 부담이 더 작아요.")).isEqualTo("DIRECTION_NOT_FROM_TOOL");
    }
}
