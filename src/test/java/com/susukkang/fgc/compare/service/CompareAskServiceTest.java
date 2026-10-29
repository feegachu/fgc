package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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

class CompareAskServiceTest {

    private final LlmGateway gateway = mock(LlmGateway.class);
    private final CompareAskService service = new CompareAskService(gateway);

    private static CompareRankingResponse ranking() {
        Item top = new Item(1, 1, "가보험", "든든건강", 784_000, 734_000, 0, 50_000, 684_000, 24.0, 90.5, null, List.of());
        return new CompareRankingResponse("r-1", "READY", "HEALTH_PROTECTION", "건강보험", LocalDate.of(2026, 7, 31), "TEST-RULE",
                "GA-CUR-2026-V1 v1", 100_000L, 12, List.of(), null, List.of(top), List.of(), CompareRankingFilters.NONE);
    }

    private static CompareRankingResponse twoItems() {
        CompareRankingResponse base = ranking();
        Item second = new Item(2, 2, "나보험", "튼튼건강", 700_000, 650_000, 0, 16_000, 634_000, 24.0, 80.0, null, List.of());
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
    @DisplayName("LLM 호출이 실패해도 순위 질문에는 서버 값으로 답한다")
    void rankQuestionFallsBackWhenGatewayFails() {
        when(gateway.call(any(), eq(CompareAskService.Output.class))).thenThrow(new IllegalStateException("down"));

        assertThat(service.ask("1위와 2위 차이가 뭐야?", twoItems()).answer()).contains("1위는 가보험 든든건강");
    }

    @Test
    @DisplayName("LLM 근거에 순위별 비교 근거가 담긴다")
    void describeIncludesRankComparison() {
        assertThat(CompareAskService.describe(twoItems())).contains("[순위별 비교 근거]",
                "2위 나보험 튼튼건강: 1위보다 보수적 순수령액이 50,000원 적어요");
    }

    @Test
    @DisplayName("순위 결과가 없으면 순위 질문도 범위 밖으로 안내한다")
    void rankQuestionWithoutRankingIsOutOfScope() {
        llmReturns(false, "");

        assertThat(service.ask("2순위인 이유 알려줘", null).source()).isEqualTo("OUT_OF_SCOPE");
    }

    private void llmReturns(Boolean inScope, String answer) {
        CompareAskService.Output output = new CompareAskService.Output();
        output.inScope = inScope;
        output.answer = answer;
        when(gateway.call(any(), eq(CompareAskService.Output.class))).thenReturn(output);
    }

    @Test
    @DisplayName("근거 안의 숫자만 쓴 답변은 그대로 돌려준다")
    void validAnswerIsReturned() {
        llmReturns(true, "가보험 든든건강은 보수적 순수령액 684,000원으로 가장 커요. 최종 선택은 설계사님이 하세요.");

        CompareAskResponse response = service.ask("왜 1위야?", ranking());

        assertThat(response.source()).isEqualTo("LLM");
        assertThat(response.answer()).contains("684,000원");
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
        when(gateway.call(any(), eq(CompareAskService.Output.class))).thenThrow(new IllegalStateException("down"));

        CompareAskResponse response = service.ask("환수가 뭐예요?", null);

        assertThat(response.source()).isEqualTo("RULE");
        assertThat(response.answer()).startsWith("환수:");
    }

    @Test
    @DisplayName("용어에 해당하지 않는 질문은 LLM 이 실패하면 안내 문장을 준다")
    void unknownQuestionGetsGuidance() {
        when(gateway.call(any(), eq(CompareAskService.Output.class))).thenThrow(new IllegalStateException("down"));

        assertThat(service.ask("가입 권유 문구 써줘", ranking()).answer()).isEqualTo(CompareAskService.FALLBACK);
    }

    @Test
    @DisplayName("LLM 근거에는 용어 설명과 순위 결과만 있고 순위가 없으면 없음으로 표시한다")
    void describeHasOnlyGlossaryAndRanking() {
        assertThat(CompareAskService.describe(ranking())).contains("[용어 설명]", "1,200% 룰", "1위 가보험 든든건강", "684,000원");
        assertThat(CompareAskService.describe(null)).contains("[순위 결과]\n없음");
    }
}
