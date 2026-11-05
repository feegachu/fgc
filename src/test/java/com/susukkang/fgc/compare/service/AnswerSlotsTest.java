package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.susukkang.fgc.compare.dto.CompareRankingFilters;
import com.susukkang.fgc.compare.dto.CompareRankingResponse;
import com.susukkang.fgc.compare.dto.CompareRankingResponse.Item;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AnswerSlotsTest {

    private static CompareRankingResponse ranking() {
        Item first = new Item(1, 1, "가보험", "든든건강", 784_000, 734_000, 50_000, 50_000, 684_000, 90.5, null, List.of());
        Item second = new Item(2, 2, "나보험", "튼튼건강", 700_000, 650_000, 0, 16_000, 634_000, 80.0, null, List.of());
        return new CompareRankingResponse("r-1", "READY", "HEALTH_PROTECTION", "건강보험", LocalDate.of(2026, 7, 31), "TEST-RULE",
                "GA-CUR-2026-V1 v1", 100_000L, 12, List.of(), null, List.of(first, second), List.of(), CompareRankingFilters.NONE);
    }

    @Test
    @DisplayName("순위별 값은 서버 값으로, 두 순위를 물으면 차이는 절댓값으로 만든다")
    void valuesContainRankValuesAndPairDifference() {
        Map<String, String> values = AnswerSlots.values(ranking(), List.of(1, 2));

        assertThat(values).containsEntry("r1.rank", "1위").containsEntry("r2.net", "634,000원")
                .containsEntry("r1.carry", "50,000원").containsEntry("r2.carry", "0원")
                .containsEntry("diff.net", "50,000원").containsEntry("diff.clawback", "34,000원");
        assertThat(AnswerSlots.values(ranking(), List.of(2))).doesNotContainKey("diff.net");
    }

    @Test
    @DisplayName("이름 자리표시자와, 차이의 큰 쪽·작은 쪽 이름은 서버가 정한다. 값이 같으면 방향은 주지 않는다")
    void namesAndDirectionComeFromServer() {
        Map<String, String> values = AnswerSlots.values(ranking(), List.of(1, 2));

        assertThat(values).containsEntry("r2.name", "나보험 튼튼건강")
                .containsEntry("diff.net.more", "가보험 든든건강").containsEntry("diff.net.less", "나보험 튼튼건강")
                .containsEntry("diff.clawback.more", "가보험 든든건강").containsEntry("diff.clawback.less", "나보험 튼튼건강");
    }

    @Test
    @DisplayName("두 값이 같으면 큰 쪽·작은 쪽 자리표시자는 없어 방향을 쓰면 답이 버려진다")
    void equalValuesHaveNoDirection() {
        Item first = new Item(1, 1, "가보험", "든든건강", 700_000, 650_000, 0, 16_000, 634_000, 90.5, null, List.of());
        Item second = new Item(2, 2, "나보험", "튼튼건강", 700_000, 650_000, 0, 16_000, 634_000, 80.0, null, List.of());
        CompareRankingResponse base = ranking();
        CompareRankingResponse equal = new CompareRankingResponse(base.rankResultId(), base.status(), base.categoryCode(),
                base.categoryLabel(), base.asOf(), base.ruleVersion(), base.policyVersion(), base.basePremium(), base.clawbackMonth(),
                List.of(), null, List.of(first, second), List.of(), CompareRankingFilters.NONE);

        Map<String, String> values = AnswerSlots.values(equal, List.of(1, 2));

        assertThat(values).containsEntry("diff.net", "0원").doesNotContainKey("diff.net.more");
        assertThat(AnswerSlots.render("{diff.net.more}가 커요.", values)).isEmpty();
    }

    @Test
    @DisplayName("자리표시자와 함께 상품 이름을 직접 쓴 답은 가려낸다")
    void detectsTypedProductName() {
        assertThat(AnswerSlots.typesProductName("{r2.name}보다 튼튼건강이 앞서요.", ranking())).isTrue();
        assertThat(AnswerSlots.typesProductName("{r2.name}가 {r1.name}보다 낮아요.", ranking())).isFalse();
    }

    @Test
    @DisplayName("자리표시자를 서버 값으로 채운다")
    void rendersSlots() {
        Map<String, String> values = AnswerSlots.values(ranking(), List.of());

        assertThat(AnswerSlots.render("{r2.rank}는 순수령액이 {r2.net}이라 {r1.rank}보다 낮아요.", values))
                .contains("2위는 순수령액이 634,000원이라 1위보다 낮아요.");
    }

    @Test
    @DisplayName("목록에 없는 자리표시자가 하나라도 있으면 채우지 않는다")
    void unknownSlotIsRejected() {
        Map<String, String> values = AnswerSlots.values(ranking(), List.of());

        assertThat(AnswerSlots.render("{r9.net}이에요.", values)).isEmpty();
        assertThat(AnswerSlots.render("{r1.net}과 {r1.secret}이에요.", values)).isEmpty();
        assertThat(AnswerSlots.render("{diff.net}이에요.", values)).isEmpty();
    }

    @Test
    @DisplayName("값에 달러·역슬래시가 있어도 그대로 들어간다")
    void valueIsInsertedLiterally() {
        assertThat(AnswerSlots.render("{a}", Map.of("a", "$1\\2원"))).contains("$1\\2원");
    }

    @Test
    @DisplayName("검증용으로 자리표시자를 지우면 남은 글에 직접 쓴 숫자만 남는다")
    void stripLeavesOnlyWrittenText() {
        assertThat(AnswerSlots.strip("{r2.net}이고 3위예요.")).doesNotContain("r2").contains("3위");
    }
}
