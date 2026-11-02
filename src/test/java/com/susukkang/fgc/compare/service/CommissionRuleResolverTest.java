package com.susukkang.fgc.compare.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.susukkang.fgc.compare.service.CommissionRuleResolver.Resolution;
import com.susukkang.fgc.compare.service.CommissionRuleResolver.RuleRow;
import com.susukkang.fgc.compare.service.CommissionRuleResolver.Run;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class CommissionRuleResolverTest {

    private static RuleRow row(long id, long item, int from, int to, String pct, boolean offering, boolean insurer, int priority) {
        return new RuleRow(id, 1L, item, from, to, new BigDecimal(pct), null, "현행수수료", "GA-CUR-2026-V1 v1",
                offering, insurer, false, false, priority);
    }

    @Test
    @DisplayName("공통 규칙과 상품 전용 규칙이 함께 있으면 상품 전용 규칙 1건만 적용한다")
    void productSpecificRuleWinsOverInsurerRule() {
        Resolution resolution = CommissionRuleResolver.resolve(List.of(
                row(1, 10, 1, 12, "650", false, true, 100),
                row(2, 10, 1, 12, "700", true, false, 100)), 12);

        assertThat(resolution.excludeReason()).isNull();
        assertThat(resolution.runs()).extracting(run -> run.rule().ruleId()).containsExactly(2L);
        assertThat(resolution.runs().get(0).from()).isEqualTo(1);
        assertThat(resolution.runs().get(0).to()).isEqualTo(12);
    }

    @Test
    @DisplayName("구체성이 같으면 priorityNo 가 작은 규칙을 적용한다")
    void lowerPriorityNumberWins() {
        Resolution resolution = CommissionRuleResolver.resolve(List.of(
                row(1, 10, 1, 12, "650", false, true, 200),
                row(2, 10, 1, 12, "700", false, true, 100)), 12);

        assertThat(resolution.runs()).extracting(run -> run.rule().ruleId()).containsExactly(2L);
    }

    @Test
    @DisplayName("우선순위까지 같은 규칙이 겹치면 임의로 고르지 않고 RULE_DUPLICATE 로 제외한다")
    void identicalPriorityIsDuplicate() {
        Resolution resolution = CommissionRuleResolver.resolve(List.of(
                row(1, 10, 1, 12, "650", false, true, 100),
                row(2, 10, 6, 12, "700", false, true, 100)), 12);

        assertThat(resolution.runs()).isEmpty();
        assertThat(resolution.excludeReason()).isEqualTo("RULE_DUPLICATE");
    }

    @Test
    @DisplayName("회차마다 이기는 규칙이 달라지면 연속 구간으로 나눈다")
    void splitsIntoRunsWhenWinnerChanges() {
        Resolution resolution = CommissionRuleResolver.resolve(List.of(
                row(1, 10, 1, 12, "650", false, true, 100),
                row(2, 10, 4, 6, "700", true, false, 100)), 12);

        List<Run> runs = resolution.runs();
        assertThat(runs).extracting(run -> run.rule().ruleId() + ":" + run.from() + "-" + run.to())
                .containsExactly("1:1-3", "2:4-6", "1:7-12");
    }

    @Test
    @DisplayName("수수료 항목이 다르면 항목마다 1건씩 적용한다")
    void eachItemResolvedSeparately() {
        Resolution resolution = CommissionRuleResolver.resolve(List.of(
                row(1, 10, 1, 12, "650", false, true, 100),
                row(2, 11, 1, 12, "100", false, true, 100)), 12);

        assertThat(resolution.runs()).extracting(run -> run.rule().itemId()).containsExactlyInAnyOrder(10L, 11L);
    }

    @Test
    @DisplayName("규칙이 없으면 NO_COMMISSION_RULE 로 제외한다")
    void emptyRulesExcluded() {
        assertThat(CommissionRuleResolver.resolve(List.of(), 12).excludeReason()).isEqualTo("NO_COMMISSION_RULE");
    }

    @Test
    @DisplayName("유효한 정책 버전이 둘 이상이면 POLICY_VERSION_AMBIGUOUS 로 제외한다")
    void multiplePolicyVersionsExcluded() {
        RuleRow other = new RuleRow(2, 2L, 10, 1, 12, new BigDecimal("700"), null, "현행수수료", "GA-CUR-2026-V2 v1",
                true, false, false, false, 100);

        Resolution resolution = CommissionRuleResolver.resolve(List.of(row(1, 10, 1, 12, "650", false, true, 100), other), 12);

        assertThat(resolution.excludeReason()).isEqualTo("POLICY_VERSION_AMBIGUOUS");
    }
}
