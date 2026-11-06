package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.susukkang.fgc.ai.service.LlmGateway;
import com.susukkang.fgc.compare.dto.CompareAskResponse;
import com.susukkang.fgc.compare.dto.CompareRankingFilters;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.dto.CompareRankingResponse.Item;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class CompareAskServiceTest {

    private final LlmGateway gateway = mock(LlmGateway.class);
    private final CompareAskService service = new CompareAskService(gateway);

    private static CompareRankingResponse ranking() {
        Item top = new Item(1, 1, "가보험", "든든건강", 784_000, 734_000, 0, 50_000, 684_000, 90.5, null, List.of());
        return new CompareRankingResponse("r-1", "READY", "HEALTH_PROTECTION", "건강보험", LocalDate.of(2026, 7, 31), "TEST-RULE",
                "GA-CUR-2026-V1 v1", 100_000L, 12, List.of(), null, List.of(top), List.of(), CompareRankingFilters.NONE);
    }

    private static CompareRankingResponse twoItems() {
        CompareRankingResponse base = ranking();
        Item second = new Item(2, 2, "나보험", "튼튼건강", 700_000, 650_000, 0, 16_000, 634_000, 80.0, null, List.of());
        return new CompareRankingResponse(base.rankResultId(), base.status(), base.categoryCode(), base.categoryLabel(), base.asOf(),
                base.ruleVersion(), base.policyVersion(), base.basePremium(), base.clawbackMonth(), base.warnings(), null,
                List.of(base.items().get(0), second), List.of(), CompareRankingFilters.NONE);
    }

    @Test
    @DisplayName("모델이 순위 질문을 범위 밖으로 거절해도 서버 값으로 2순위 이유를 풀어 답한다")
    void rankQuestionIsAnsweredEvenWhenModelRefuses() {
        llmReturns(false, "");

        CompareAskResponse response = service.ask("2순위인 이유 알려줘", twoItems());

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).contains("2위는 나보험 튼튼건강", "1위보다 보수적 순수령액이 50,000원 적어요",
                "수령 예상액은 1위보다 84,000원 적고", "환수 노출액은 1위보다 34,000원 더 작아요");
    }

    @Test
    @DisplayName("int 범위를 넘는 긴 순위 번호도 예외 없이 1위·2위 비교로 답한다")
    void hugeRankNumberFallsBackToDefaultComparison() {
        llmReturns(false, "");

        CompareAskResponse response = service.ask("99999999999999위 이유 알려줘", twoItems());

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).contains("2위는 나보험 튼튼건강");
    }

    @Test
    @DisplayName("항목 수를 넘는 순위 번호는 기본 순위로 대체한다")
    void outOfRangeRankNumberUsesDefault() {
        assertThat(CompareAskService.rankAnswer(twoItems().items(), "5순위 이유")).contains("2위는 나보험 튼튼건강");
    }

    @Test
    @DisplayName("LLM 호출이 실패해도 순위 질문에는 서버 값으로 답한다")
    void rankQuestionFallsBackWhenGatewayFails() {
        gatewayFails();

        assertThat(service.ask("1위와 2위 차이가 뭐야?", twoItems()).answer()).contains("1위는 가보험 든든건강");
    }

    @Test
    @DisplayName("LLM 근거에 순위별 비교 근거가 담긴다")
    void describeIncludesRankComparison() {
        assertThat(CompareAskService.describe(twoItems())).contains("[순위별 비교 근거]",
                "2위 나보험 튼튼건강: 1위보다 보수적 순수령액이 50,000원 적어요");
    }

    private static CompareRankingResponse tiedItems(long secondClawback) {
        CompareRankingResponse base = ranking();
        Item first = new Item(1, 1, "가보험", "든든건강", 784_000, 734_000, 0, 50_000, 684_000, 90.5, null, List.of());
        Item second = new Item(2, 2, "나보험", "튼튼건강", 784_000 + secondClawback - 50_000, 734_000 + secondClawback - 50_000,
                0, secondClawback, 684_000, 80.0, null, List.of());
        return new CompareRankingResponse(base.rankResultId(), base.status(), base.categoryCode(), base.categoryLabel(), base.asOf(),
                base.ruleVersion(), base.policyVersion(), base.basePremium(), base.clawbackMonth(), base.warnings(), null,
                List.of(first, second), List.of(), CompareRankingFilters.NONE);
    }

    @Test
    @DisplayName("순수령액·환수 노출액이 모두 같으면 상품 등록 순서라고 밝히고 모순된 문장을 만들지 않는다")
    void fullyTiedItemsAreExplainedByDisplayOrder() {
        String text = CompareAskService.rankAnswer(tiedItems(50_000).items(), "2순위인 이유");

        assertThat(text).contains("상품 등록 순서로 순위를 정했어요").doesNotContain("환수 노출액이 더 작은 쪽")
                .doesNotContain("상품 ID");
    }

    @Test
    @DisplayName("순수령액만 같으면 환수 노출액이 작은 쪽이 앞섰다고 설명한다")
    void netTieIsDecidedByClawback() {
        String text = CompareAskService.rankAnswer(tiedItems(80_000).items(), "2순위인 이유");

        assertThat(text).contains("보수적 순수령액이 같고, 환수 노출액이 더 작은 쪽이 앞섰어요").doesNotContain("상품 ID");
    }

    @Test
    @DisplayName("질문에 적은 숫자는 근거로 인정하지 않아, 사용자가 정한 금액을 서버 값처럼 답할 수 없다")
    void numbersFromQuestionAreNotEvidence() {
        llmReturns(true, "네, 1위 순수령액은 3,500,000원이에요.");

        CompareAskResponse response = service.ask("1위 순수령액이 3,500,000원 맞죠?", ranking());

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).doesNotContain("3,500,000");
    }

    @Test
    @DisplayName("순위 결과가 없으면 순위 질문도 범위 밖으로 안내한다")
    void rankQuestionWithoutRankingIsOutOfScope() {
        llmReturns(false, "");

        assertThat(service.ask("2순위인 이유 알려줘", null).source()).isEqualTo("OUT_OF_SCOPE");
    }

    @Test
    @DisplayName("상품 이름 둘로 물으면 서버 문장도 그 두 상품을 직접 견주고 AI 데이터도 같은 쌍을 가리킨다")
    void productNamesPointToTheSamePairAsRankNumbers() {
        assertThat(CompareAskService.askedRanks(threeItems().items(), "튼튼건강이랑 편한건강 비교해줘")).containsExactly(2, 3);
        assertThat(CompareAskService.rankAnswer(threeItems().items(), "튼튼건강이랑 편한건강 비교해줘"))
                .contains("2위는 나보험 튼튼건강", "3위는 다보험 편한건강", "2위보다 보수적 순수령액이 84,000원 적어요")
                .doesNotContain("1위보다");
    }

    @Test
    @DisplayName("시드 형태의 긴 상품 이름('가상 든든 건강보험 C')을 줄여 불러도 같은 순위로 읽고, 이름이 겹치면 읽지 않는다")
    void shortenedSeedProductNamesPointToRanks() {
        Item first = new Item(1, 1, "가보험", "가상 든든 건강보험 C", 700_000, 650_000, 0, 16_000, 634_000, 90.5, null, List.of());
        Item second = new Item(2, 2, "나보험", "가상 장기 건강보험 E", 600_000, 580_000, 0, 30_000, 550_000, 80.0, null, List.of());
        Item same = new Item(3, 3, "다보험", "가상 장기 건강보험 F", 500_000, 480_000, 0, 30_000, 450_000, 70.0, null, List.of());
        List<Item> items = List.of(first, second, same);

        assertThat(CompareAskService.askedRanks(items, "든든 건강보험이랑 가상 장기 건강보험 E 비교해줘")).containsExactly(1, 2);
        assertThat(CompareAskService.askedRanks(items, "든든 건강보험 순위가 왜 이래?")).containsExactly(1);
        assertThat(CompareAskService.askedRanks(items, "장기 건강보험이랑 비교해줘")).isEmpty();
    }

    @Test
    @DisplayName("다른 상품 이름 안에 든 짧은 이름은 따로 맞지 않는다(변액 연금저축보험 ⊃ 연금저축보험)")
    void nameContainedInAnotherNameIsNotMatchedSeparately() {
        Item o = new Item(1, 1, "가보험", "가상 연금저축보험 O", 700_000, 650_000, 0, 16_000, 634_000, 90.5, null, List.of());
        Item pItem = new Item(2, 2, "나보험", "가상 변액 연금저축보험 P", 600_000, 580_000, 0, 30_000, 550_000, 80.0, null, List.of());
        List<Item> items = List.of(o, pItem);

        assertThat(CompareAskService.askedRanks(items, "변액 연금저축보험은 왜 이 순위야?")).containsExactly(2);
        assertThat(CompareAskService.askedRanks(items, "가상 변액 연금저축보험 P 왜 이래?")).containsExactly(2);
        // '연금저축보험'은 카테고리 이름과 같아 줄인 이름으로는 O 로 읽지 않는다. 전체 이름을 적으면 읽는다.
        assertThat(CompareAskService.askedRanks(items, "연금저축보험이랑 변액 연금저축보험 비교해줘")).containsExactly(2);
        assertThat(CompareAskService.askedRanks(items, "가상 연금저축보험 O랑 변액 연금저축보험 비교해줘")).containsExactly(1, 2);
    }

    private static CompareRankingResponse rankingOf(Item... items) {
        CompareRankingResponse base = ranking();
        return new CompareRankingResponse(base.rankResultId(), base.status(), base.categoryCode(), base.categoryLabel(), base.asOf(),
                base.ruleVersion(), base.policyVersion(), base.basePremium(), base.clawbackMonth(), base.warnings(), null,
                List.of(items), List.of(), CompareRankingFilters.NONE);
    }

    private static List<Item> termItems() {
        return List.of(
                new Item(1, 1, "가보험", "가상 정기보험 K", 700_000, 650_000, 0, 16_000, 634_000, 90.5, null, List.of()),
                new Item(2, 2, "나보험", "가상 CEO 정기보험 L", 600_000, 580_000, 0, 30_000, 550_000, 80.0, null, List.of()),
                new Item(3, 3, "다보험", "가상 경영인정기보험", 500_000, 480_000, 0, 30_000, 450_000, 70.0, null, List.of()));
    }

    @Test
    @DisplayName("카테고리 이름(정기보험)만 말하면 같은 이름으로 줄여지는 상품으로 읽지 않고, 적은 순위 번호를 그대로 쓴다")
    void categoryNameIsNotReadAsAProduct() {
        List<Item> items = termItems();

        assertThat(CompareAskService.askedRanks(items, "정기보험 2위랑 3위 차이 알려줘")).containsExactly(2, 3);
        assertThat(CompareAskService.askedRanks(items, "정기보험 3위는 왜 3위야?")).containsExactly(3);
        assertThat(CompareAskService.rankAnswer(items, "정기보험 2위랑 3위 차이 알려줘"))
                .contains("2위는 나보험 가상 CEO 정기보험 L", "3위는 다보험 가상 경영인정기보험").doesNotContain("1위");
        assertThat(CompareAskService.askedRanks(items, "가상 정기보험 K는 왜 1등이야?")).containsExactly(1);
    }

    @Test
    @DisplayName("적은 순위 번호는 이름으로 찾은 순위에 밀려나지 않는다")
    void rankNumbersComeBeforeNamedRanks() {
        assertThat(CompareAskService.askedRanks(threeItems().items(), "2위랑 3위 중 든든건강보다 나은 건?")).containsExactly(2, 3);
        assertThat(CompareAskService.askedRanks(threeItems().items(), "3위랑 든든건강 비교해줘")).containsExactly(1, 3);
    }

    @Test
    @DisplayName("카테고리 이름이 든 소개 질문('연금저축보험 비교 챗봇은 무슨 기능이야?')은 소개 문장으로 답한다")
    void introQuestionWithCategoryNameIsIntro() {
        llmReturns(false, "");
        CompareRankingResponse savings = rankingOf(
                new Item(1, 1, "가보험", "가상 연금저축보험 O", 700_000, 650_000, 0, 16_000, 634_000, 90.5, null, List.of()),
                new Item(2, 2, "나보험", "가상 변액 연금저축보험 P", 600_000, 580_000, 0, 30_000, 550_000, 80.0, null, List.of()));

        assertThat(service.ask("연금저축보험 비교 챗봇은 무슨 기능이야?", savings).answer()).isEqualTo(CompareAskService.INTRO);
    }

    @Test
    @DisplayName("용어를 함께 물은 소개 질문에는 순위 비교 문장을 붙이지 않는다(AI 거절·성공·실패)")
    void introQuestionWithGlossaryTermHasNoRankSentence() {
        String question = "상품 비교 챗봇은 무슨 기능이야? 이월도 알려줘";

        llmReturns(false, "");
        assertThat(service.ask(question, threeItems()).answer()).startsWith("이월:").doesNotContain("1위는");

        llmReturns(true, "비교 결과와 이월 같은 용어를 설명해 드려요.");
        assertThat(service.ask(question, threeItems()).answer()).isEqualTo("비교 결과와 이월 같은 용어를 설명해 드려요.");

        gatewayFails();
        assertThat(service.ask(question, threeItems()).answer()).startsWith("이월:").doesNotContain("1위는");
    }

    @Test
    @DisplayName("두 순위 비교 답변은 이월 예정액을 0원이어도 서버 값으로 보여 준다")
    void pairAnswerIncludesCarryoverEvenWhenZero() {
        assertThat(CompareAskService.rankAnswer(threeItems().items(), "2위랑 3위 이월액 비교해줘"))
                .contains("이월 예정액은 2위 0원, 3위 0원이에요.");
    }

    @Test
    @DisplayName("'1등급' 같은 등급 표현은 순위 번호로 읽지 않는다")
    void gradeWordIsNotARankNumber() {
        assertThat(CompareAskService.askedRanks(threeItems().items(), "3위랑 2위 중 1등급인 건?")).containsExactly(2, 3);
    }

    @Test
    @DisplayName("순위 결과가 있어도 챗봇 소개 질문은 순위 금액이 아니라 소개 문장으로 답한다")
    void introQuestionBeatsRankQuestionWhenNoRankIsNamed() {
        llmReturns(false, "");

        assertThat(service.ask("상품 비교 챗봇은 무슨 기능이야?", threeItems()).answer()).isEqualTo(CompareAskService.INTRO);
        assertThat(service.ask("1위랑 2위 비교해줘", threeItems()).answer()).contains("1위는 가보험 든든건강");
    }

    @Test
    @DisplayName("AI가 소개 질문에 정상 답해도 순위 금액 문장을 앞에 붙이지 않는다")
    void introAnswerFromLlmHasNoRankSentence() {
        llmReturns(true, "상품 비교 결과를 풀어서 설명해 드려요.");

        CompareAskResponse response = service.ask("상품 비교 챗봇은 무슨 기능이야?", threeItems());

        assertThat(response.source()).isEqualTo("LLM");
        assertThat(response.answer()).isEqualTo("상품 비교 결과를 풀어서 설명해 드려요.");
    }

    @Test
    @DisplayName("순위 결과가 있어도 '1등급이 뭐야?'는 순위 문장이 아니라 용어 설명으로 답한다")
    void gradeQuestionIsGlossaryNotRank() {
        llmReturns(false, "");

        CompareAskResponse response = service.ask("협회 1등급이 뭐야?", threeItems());

        assertThat(response.answer()).doesNotContain("1위는");
        assertThat(CompareAskService.isRankQuestion("1등급이 뭐야?")).isFalse();
    }

    @Test
    @DisplayName("'2등급과 3등급 차이'는 '차이'가 있어도 순위 비교가 아니라 등급 용어 설명으로 답한다(AI 거절·실패·성공)")
    void gradeDifferenceQuestionIsNotRankComparison() {
        String question = "2등급과 3등급 차이가 뭐야?";

        llmReturns(false, "");
        assertThat(service.ask(question, threeItems()).answer()).startsWith("협회 수수료 등급:").doesNotContain("1위는");

        llmReturns(true, "협회가 공시한 등급끼리의 차이예요.");
        CompareAskResponse ok = service.ask(question, threeItems());
        assertThat(ok.source()).isEqualTo("LLM");
        assertThat(ok.answer()).isEqualTo("협회가 공시한 등급끼리의 차이예요.");

        gatewayFails();
        assertThat(service.ask(question, threeItems()).answer()).startsWith("협회 수수료 등급:").doesNotContain("1위는");
    }

    @Test
    @DisplayName("순위 단어 없이 상품 이름 둘로만 물어도 순위 질문으로 보고 두 상품의 이월 예정액을 서버 값으로 답한다")
    void twoProductNamesWithoutRankWordAreRankQuestion() {
        gatewayFails();

        CompareAskResponse response = service.ask("튼튼건강이랑 편한건강 중 이월 예정액이 어느 쪽이 더 커?", threeItems());

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).contains("2위는 나보험 튼튼건강", "3위는 다보험 편한건강", "이월 예정액은 2위 0원, 3위 0원이에요.")
                .doesNotStartWith("이월:");
    }

    @Test
    @DisplayName("보험사만 다르고 이름이 같은 상품은 이름만으로 어느 순위인지 정하지 않는다")
    void duplicateFullProductNameIsNotResolvedToARank() {
        Item first = new Item(1, 1, "가보험", "튼튼건강", 700_000, 650_000, 0, 16_000, 634_000, 90.5, null, List.of());
        Item second = new Item(2, 2, "나보험", "튼튼건강", 600_000, 580_000, 0, 30_000, 550_000, 80.0, null, List.of());
        Item third = new Item(3, 3, "다보험", "편한건강", 500_000, 480_000, 0, 30_000, 450_000, 70.0, null, List.of());
        List<Item> items = List.of(first, second, third);

        assertThat(CompareAskService.askedRanks(items, "튼튼건강 순위가 왜 이래?")).isEmpty();
        assertThat(CompareAskService.askedRanks(items, "튼튼건강이랑 편한건강 비교해줘")).containsExactly(3);
        // 보험사를 붙여 쓰면 어느 상품인지 알 수 있다(AI 답 검사와 같은 규칙).
        assertThat(CompareAskService.askedRanks(items, "나보험 튼튼건강 순위가 왜 이래?")).containsExactly(2);
    }

    private static CompareRankingResponse threeItems() {
        CompareRankingResponse two = twoItems();
        Item third = new Item(3, 3, "다보험", "편한건강", 600_000, 580_000, 0, 30_000, 550_000, 70.0, null, List.of());
        return new CompareRankingResponse(two.rankResultId(), two.status(), two.categoryCode(), two.categoryLabel(), two.asOf(),
                two.ruleVersion(), two.policyVersion(), two.basePremium(), two.clawbackMonth(), two.warnings(), null,
                List.of(two.items().get(0), two.items().get(1), third), List.of(), CompareRankingFilters.NONE);
    }

    @Test
    @DisplayName("순위 둘을 물으면 1위가 아니라 그 두 상품을 직접 견준다")
    void twoRanksAreComparedDirectly() {
        String text = CompareAskService.rankAnswer(threeItems().items(), "3위랑 2위를 비교해줘");

        assertThat(text).contains("2위는 나보험 튼튼건강", "3위는 다보험 편한건강",
                "2위보다 보수적 순수령액이 84,000원 적어요", "환수 노출액은 2위보다 14,000원 더 커요")
                .doesNotContain("1위보다");
    }

    @Test
    @DisplayName("순위 번호는 중복 없이 작은 순서로 최대 둘까지 읽고 범위 밖 번호는 버린다")
    void askedRanksAreDistinctSortedAndInRange() {
        assertThat(CompareAskService.askedRanks(threeItems().items(), "3위랑 2위, 2위, 9위")).containsExactly(2, 3);
        assertThat(CompareAskService.askedRanks(threeItems().items(), "9위 이유")).isEmpty();
    }

    @Test
    @DisplayName("순위 결과가 있으면 모델에 상품 목록과 도구를 주고 금액은 주지 않는다")
    void toolPathSendsItemListAndToolsButNoAmounts() {
        llmReturns(true, "나보험 튼튼건강은 환수 부담이 더 작아요.");

        CompareAskResponse response = service.ask("3위랑 2위를 비교해줘", threeItems());

        ArgumentCaptor<LlmGateway.LlmRequest> captor = ArgumentCaptor.forClass(LlmGateway.LlmRequest.class);
        ArgumentCaptor<List<LlmGateway.ToolSpec>> tools = ArgumentCaptor.forClass(List.class);
        verify(gateway).callWithTools(captor.capture(), eq(CompareAskService.Output.class), tools.capture(), any(), anyInt());
        assertThat(captor.getValue().data()).contains("[상품 목록]", "2위 나보험 튼튼건강", "3위 다보험 편한건강").doesNotContain("634,000");
        assertThat(tools.getValue()).extracting(LlmGateway.ToolSpec::name).containsExactly("get_item", "compare", "explain_term");
        assertThat(response.source()).isEqualTo("LLM");
        // 순위 질문이므로 서버가 만든 순위·금액 문장이 앞에 붙고, AI 문장은 숫자 없이 뒤에 온다.
        assertThat(response.answer()).startsWith("2위는 나보험 튼튼건강").endsWith(" 나보험 튼튼건강은 환수 부담이 더 작아요.");
        verify(gateway, never()).call(any(), eq(CompareAskService.Output.class));
    }

    private void llmReturns(Boolean inScope, String answer) {
        CompareAskService.Output output = new CompareAskService.Output();
        output.inScope = inScope;
        output.answer = answer;
        when(gateway.call(any(), eq(CompareAskService.Output.class))).thenReturn(output);
        when(gateway.callWithTools(any(), eq(CompareAskService.Output.class), any(), any(), anyInt())).thenReturn(output);
    }

    /** 모델 호출이 실패한다. 순위 결과가 있으면 도구 경로(callWithTools)를 타므로 두 경로를 모두 실패시킨다. */
    private void gatewayFails() {
        when(gateway.call(any(), eq(CompareAskService.Output.class))).thenThrow(new IllegalStateException("down"));
        when(gateway.callWithTools(any(), eq(CompareAskService.Output.class), any(), any(), anyInt()))
                .thenThrow(new IllegalStateException("down"));
    }

    /** 모델이 도구를 (이름, 인자 JSON) 순서대로 부른 뒤 답하는 것을 흉내 낸다. */
    private void llmCallsTools(Boolean inScope, String answer, String... nameAndArgs) {
        CompareAskService.Output output = new CompareAskService.Output();
        output.inScope = inScope;
        output.answer = answer;
        when(gateway.callWithTools(any(), eq(CompareAskService.Output.class), any(), any(), anyInt())).thenAnswer(invocation -> {
            LlmGateway.ToolRunner runner = invocation.getArgument(3, LlmGateway.ToolRunner.class);
            for (int i = 0; i < nameAndArgs.length; i += 2) {
                runner.run(nameAndArgs[i], nameAndArgs[i + 1]);
            }
            return output;
        });
    }

    @Test
    @DisplayName("순위 질문은 서버가 만든 순위·금액 문장 뒤에 숫자 없는 AI 풀이를 붙인다")
    void toolAnswerIsPrefixedWithServerSentence() {
        llmCallsTools(true, "가보험 든든건강은 환수 부담이 작아 가장 앞섰어요.", "get_item", "{\"rank\":1}");

        CompareAskResponse response = service.ask("왜 1위야?", ranking());

        assertThat(response.source()).isEqualTo("LLM");
        assertThat(response.answer()).startsWith("1위는 가보험 든든건강예요. 보수적 순수령액은 684,000원이에요.")
                .endsWith(" 가보험 든든건강은 환수 부담이 작아 가장 앞섰어요.");
    }

    @Test
    @DisplayName("도구로 얻은 금액이라도 AI 문장에 숫자를 쓰면 서버 문장으로 대체한다")
    void toolAmountInAiTextIsReplaced() {
        llmCallsTools(true, "1위 가보험 든든건강의 보수적 순수령액은 684,000원이에요.", "get_item", "{\"rank\":1}");

        CompareAskResponse response = service.ask("왜 1위야?", ranking());

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).startsWith("1위는 가보험 든든건강예요.");
    }

    @Test
    @DisplayName("AI 답에 금액 문장이 섞여 있으면 그 문장만 빼고 나머지 설명을 서버 문장 뒤에 붙인다")
    void sentencesWithAmountsAreDroppedAndTheRestKept() {
        llmCallsTools(true, "가보험 든든건강은 환수 부담이 작아요. 보수적 순수령액은 684,000원이에요. 그래서 앞섰어요.",
                "get_item", "{\"rank\":1}");

        CompareAskResponse response = service.ask("왜 1위야?", ranking());

        assertThat(response.source()).isEqualTo("LLM");
        assertThat(response.answer()).startsWith("1위는 가보험 든든건강예요. 보수적 순수령액은 684,000원이에요.")
                .endsWith(" 가보험 든든건강은 환수 부담이 작아요. 그래서 앞섰어요.");
        assertThat(response.answer().indexOf("684,000원")).isEqualTo(response.answer().lastIndexOf("684,000원"));
    }

    @Test
    @DisplayName("AI 답에 순위 표기만 있고 금액이 없으면 그대로 쓴다")
    void rankLabelsInAiTextAreKept() {
        llmCallsTools(true, "1위인 가보험 든든건강은 환수 부담이 작아서 앞섰어요.", "get_item", "{\"rank\":1}");

        CompareAskResponse response = service.ask("1위가 어떤 상품이야?", ranking());

        assertThat(response.source()).isEqualTo("LLM");
        assertThat(response.answer()).endsWith(" 1위인 가보험 든든건강은 환수 부담이 작아서 앞섰어요.");
    }

    @Test
    @DisplayName("질문이 두 상품을 가리키면 AI 문장이 다른 상품을 말할 때 서버 문장으로 대체한다")
    void aiTextAboutOtherProductIsReplaced() {
        llmCallsTools(true, "가보험 든든건강은 환수 부담이 가장 작아요.", "compare", "{\"rankA\":2,\"rankB\":3}");

        CompareAskResponse response = service.ask("3위랑 2위 비교해줘", threeItems());

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).startsWith("2위는 나보험 튼튼건강").doesNotContain("가장 작아요");
    }

    @Test
    @DisplayName("같은 보험사 상품이 더 있어도, 질문한 두 상품만 말한 맞는 답은 버리지 않는다")
    void sharedInsurerDoesNotDropCorrectAnswer() {
        CompareRankingResponse base = ranking();
        CompareRankingResponse shared = new CompareRankingResponse(base.rankResultId(), base.status(), base.categoryCode(),
                base.categoryLabel(), base.asOf(), base.ruleVersion(), base.policyVersion(), base.basePremium(), base.clawbackMonth(),
                base.warnings(), null, List.of(
                        new Item(1, 1, "가보험", "든든건강", 784_000, 734_000, 0, 50_000, 684_000, 90.5, null, List.of()),
                        new Item(2, 2, "나보험", "튼튼건강", 700_000, 650_000, 0, 16_000, 634_000, 80.0, null, List.of()),
                        new Item(3, 3, "가보험", "편한건강", 600_000, 580_000, 0, 30_000, 550_000, 70.0, null, List.of())),
                List.of(), CompareRankingFilters.NONE);
        llmCallsTools(true, "나보험 튼튼건강이 가보험 든든건강보다 환수 노출액이 더 작아요.", "compare", "{\"rankA\":1,\"rankB\":2}");

        CompareAskResponse response = service.ask("1위랑 2위 비교해줘", shared);

        assertThat(response.source()).isEqualTo("LLM");
        assertThat(response.answer()).endsWith(" 나보험 튼튼건강이 가보험 든든건강보다 환수 노출액이 더 작아요.");
    }

    @Test
    @DisplayName("질문을 따라 줄여 쓴 상품 이름의 AI 답도 방향과 다른 상품 언급을 검사한다")
    void shortenedNamesInAiAnswerAreChecked() {
        String question = "든든 건강보험이랑 장기 건강보험 중 환수 부담이 작은 건?";
        CompareRankingResponse seed = CompareAskToolsTest.seedNamedRanking();

        llmCallsTools(true, "든든 건강보험이 장기 건강보험보다 환수 부담이 더 작아요.", "compare", "{\"rankA\":1,\"rankB\":2}");
        assertThat(service.ask(question, seed).source()).as("반대 방향").isEqualTo("RULE");

        llmCallsTools(true, "튼튼 건강보험은 환수 부담이 중간이에요.", "compare", "{\"rankA\":1,\"rankB\":2}");
        assertThat(service.ask(question, seed).source()).as("질문하지 않은 상품").isEqualTo("RULE");

        llmCallsTools(true, "장기 건강보험이 든든 건강보험보다 환수 부담이 더 작아요.", "compare", "{\"rankA\":1,\"rankB\":2}");
        CompareAskResponse ok = service.ask(question, seed);
        assertThat(ok.source()).isEqualTo("LLM");
        assertThat(ok.answer()).startsWith("1위는 가보험 가상 든든 건강보험 C").endsWith(" 장기 건강보험이 든든 건강보험보다 환수 부담이 더 작아요.");
    }

    @Test
    @DisplayName("도구를 부르지 않고 금액을 쓰면 서버 문장으로 대체한다")
    void amountWithoutToolCallIsReplaced() {
        llmReturns(true, "1위 가보험 든든건강의 보수적 순수령액은 684,000원이에요.");

        CompareAskResponse response = service.ask("왜 1위야?", ranking());

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).startsWith("1위는 가보험 든든건강예요.");
    }

    @Test
    @DisplayName("AI 문장에 금액을 쓰면(다른 순위의 금액이어도) 서버 문장으로 대체한다")
    void amountAttachedToWrongRankIsReplaced() {
        llmCallsTools(true, "3위 다보험 편한건강의 보수적 순수령액은 634,000원이에요.",
                "get_item", "{\"rank\":2}", "get_item", "{\"rank\":3}");

        CompareAskResponse response = service.ask("3위 순수령액이 얼마야?", threeItems());

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).contains("3위는 다보험 편한건강").doesNotContain("634,000원이에요. 3위");
    }

    @Test
    @DisplayName("많고 적음이 서버 값과 반대인 비교 답은 서버 문장으로 대체한다")
    void reversedDirectionIsReplaced() {
        llmCallsTools(true, "다보험 편한건강이 나보험 튼튼건강보다 보수적 순수령액이 더 많아요.",
                "compare", "{\"rankA\":2,\"rankB\":3}");

        CompareAskResponse response = service.ask("3위랑 2위 순수령액 차이가 뭐야?", threeItems());

        assertThat(response.source()).isEqualTo("RULE");
    }

    @Test
    @DisplayName("많고 적음이 서버 값과 같은 비교 답은 서버 문장 뒤에 붙여 낸다")
    void correctDirectionIsReturned() {
        llmCallsTools(true, "다보험 편한건강이 나보험 튼튼건강보다 보수적 순수령액이 더 적어요.",
                "compare", "{\"rankA\":2,\"rankB\":3}");

        CompareAskResponse response = service.ask("3위랑 2위 순수령액 차이가 뭐야?", threeItems());

        assertThat(response.source()).isEqualTo("LLM");
        // 금액은 서버 문장에만 있고, AI 문장은 방향만 말로 쓴다.
        assertThat(response.answer()).contains("2위보다 보수적 순수령액이 84,000원 적어요")
                .endsWith(" 다보험 편한건강이 나보험 튼튼건강보다 보수적 순수령액이 더 적어요.");
    }

    @Test
    @DisplayName("특정 상품을 이득이라고 권하는 답은 서버 문장으로 대체한다")
    void recommendationAnswerIsReplaced() {
        llmReturns(true, "고객에게 가장 이득이 되는 상품은 가보험 든든건강입니다.");

        CompareAskResponse response = service.ask("고객한테는 어떤 게 이득이야?", ranking());

        assertThat(response.source()).isNotEqualTo("LLM");
        assertThat(response.answer()).doesNotContain("이득이 되는");
    }

    @Test
    @DisplayName("도구 호출이 실패하면 서버 문장으로 대체한다")
    void gatewayFailureFallsBackToRuleAnswer() {
        when(gateway.callWithTools(any(), eq(CompareAskService.Output.class), any(), any(), anyInt()))
                .thenThrow(new com.susukkang.fgc.common.exception.FgcBusinessException(com.susukkang.fgc.common.exception.FgcErrorCode.AI_001));

        CompareAskResponse response = service.ask("왜 1위야?", ranking());

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).startsWith("1위는 가보험 든든건강예요.");
    }

    @Test
    @DisplayName("용어 질문은 숫자 없는 AI 답변을 그대로 돌려주고, 용어 이름 1,200%는 허용한다")
    void conceptQuestionReturnsAiProse() {
        llmReturns(true, "1,200% 룰은 첫해 수수료가 한도를 넘지 않게 하는 규칙이에요. 넘는 부분은 이월돼요.");

        CompareAskResponse response = service.ask("1200% 룰이 뭐야?", null);

        assertThat(response.source()).isEqualTo("LLM");
        assertThat(response.answer()).isEqualTo("1,200% 룰은 첫해 수수료가 한도를 넘지 않게 하는 규칙이에요. 넘는 부분은 이월돼요.");
    }

    @ParameterizedTest
    @DisplayName("근거에 있는 값을 다른 항목에 옮겨 쓰거나 한글·전각 숫자로 쓴 AI 답변은 서버 문장(RULE)으로 대체한다")
    @ValueSource(strings = {
            "가보험 든든건강의 보수적 순수령액은 50,000원이에요.",
            "가보험 든든건강의 보수적 순수령액은 구백만원이에요.",
            "가보험 든든건강의 보수적 순수령액은 ９９９９９９９원이에요."})
    void numbersFromAiAreReplacedByServerFacts(String answer) {
        llmReturns(true, answer);

        CompareAskResponse response = service.ask("1위 순수령액이 얼마야?", ranking());

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).contains("보수적 순수령액은 684,000원이에요").doesNotContain(answer);
    }

    @Test
    @DisplayName("주민번호·전화번호로 보이는 질문은 모델에 보내지 않고 개인정보 입력 금지 안내로 답한다")
    void personalInfoIsNeverSentToTheModel() {
        CompareAskResponse rrn = service.ask("고객 900101-1234567 에게 맞는 상품 뭐야?", ranking());
        CompareAskResponse phone = service.ask("010-1234-5678 고객한테 추천할 상품?", ranking());
        CompareAskResponse spacedRrn = service.ask("주민번호 900101 - 1234567 고객이에요", ranking());
        CompareAskResponse email = service.ask("kim.cs@example.co.kr 고객 상담 중인데요", ranking());

        assertThat(rrn).isEqualTo(new CompareAskResponse(CompareAskService.PERSONAL_INFO, "RULE"));
        assertThat(phone.answer()).isEqualTo(CompareAskService.PERSONAL_INFO);
        assertThat(spacedRrn.answer()).isEqualTo(CompareAskService.PERSONAL_INFO);
        assertThat(email.answer()).isEqualTo(CompareAskService.PERSONAL_INFO);
        verify(gateway, never()).call(any(), any());
    }

    @Test
    @DisplayName("금액처럼 긴 숫자는 전화번호로 오인하지 않는다")
    void longAmountIsNotTreatedAsPhoneNumber() {
        llmReturns(false, "");

        assertThat(service.ask("1000000000원이면 2순위는?", twoItems()).answer()).doesNotContain(CompareAskService.PERSONAL_INFO);
    }

    @ParameterizedTest
    @DisplayName("모델이 거절해도 용어 질문은 용어 설명으로 답한다")
    @ValueSource(strings = {"협회등급이 뭐야?", "순수령액이 어떻게 되는거지", "이월이 뭐예요"})
    void glossaryQuestionIsAnsweredEvenWhenModelRefuses(String question) {
        llmReturns(false, "");

        CompareAskResponse response = service.ask(question, null);

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).doesNotContain(CompareAskService.OUT_OF_SCOPE);
    }

    @ParameterizedTest
    @DisplayName("모델이 거절해도 챗봇 소개 질문은 소개 문장으로 답한다")
    @ValueSource(strings = {"너는 뭘 알려주는거야", "니 역할은 뭐니", "너는 누구야"})
    void introQuestionIsAnsweredEvenWhenModelRefuses(String question) {
        llmReturns(false, "");

        CompareAskResponse response = service.ask(question, ranking());

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).isEqualTo(CompareAskService.INTRO);
    }

    @Test
    @DisplayName("범위 밖 질문은 고정 안내 문장으로 답한다")
    void outOfScope() {
        llmReturns(false, "");

        CompareAskResponse response = service.ask("오늘 날씨 어때?", ranking());

        assertThat(response.source()).isEqualTo("OUT_OF_SCOPE");
        assertThat(response.answer()).isEqualTo(CompareAskService.OUT_OF_SCOPE);
    }

    @Test
    @DisplayName("근거에 없는 숫자가 나오면 버리고 용어 설명으로 대체한다")
    void inventedNumberFallsBackToGlossary() {
        llmReturns(true, "이월은 한도를 넘으면 999,999원이 생겨요.");

        CompareAskResponse response = service.ask("이월이 뭐야?", null);

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).contains("이월:").doesNotContain("999");
    }

    @Test
    @DisplayName("단정 표현이 있으면 버린다")
    void forbiddenPhraseFallsBack() {
        llmReturns(true, "1위 상품을 반드시 선택하세요.");

        assertThat(service.ask("뭐 골라?", ranking()).source()).isEqualTo("RULE");
    }

    @Test
    @DisplayName("LLM 호출이 실패해도 용어 질문에는 용어 설명으로 답한다")
    void gatewayFailureUsesGlossary() {
        gatewayFails();

        CompareAskResponse response = service.ask("환수가 뭐예요?", null);

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).startsWith("환수:");
    }

    @Test
    @DisplayName("용어에 해당하지 않는 질문은 LLM 이 실패하면 안내 문장을 준다")
    void unknownQuestionGetsGuidance() {
        gatewayFails();

        assertThat(service.ask("가입 권유 문구 써줘", ranking()).answer()).isEqualTo(CompareAskService.FALLBACK);
    }

    @Test
    @DisplayName("LLM 근거에는 용어 설명과 순위 결과만 있고 순위가 없으면 없음으로 표시한다")
    void describeHasOnlyGlossaryAndRanking() {
        assertThat(CompareAskService.describe(ranking())).contains("[용어 설명]", "1,200% 룰", "1위 가보험 든든건강", "684,000원");
        assertThat(CompareAskService.describe(null)).contains("[순위 결과]\n없음");
    }
}
