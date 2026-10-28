package com.susukkang.fgc.compare.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 설명 : 조건에 맞는 수수료 규칙 행들에서 (수수료 항목, 회차)마다 적용할 규칙을 1건만 고른다.
 * 기존 엔진(CommissionPolicyServiceImpl)과 같은 순서로 상품 판매버전 &gt; 보험사 &gt; 설계사 직급 &gt; 조직 &gt; priorityNo 를 비교하고,
 * 우선순위까지 같은 규칙이 둘 이상이면 임의로 고르지 않고 RULE_DUPLICATE 로 제외한다.
 * 유효한 정책 버전이 둘 이상이어도 제외한다(기존 엔진은 활성 정책이 정확히 1건일 때만 진행한다).
 */
public final class CommissionRuleResolver {

    private static final Comparator<RuleRow> ORDER = Comparator.comparing(RuleRow::offeringSpecific, Comparator.reverseOrder())
            .thenComparing(RuleRow::insurerSpecific, Comparator.reverseOrder())
            .thenComparing(RuleRow::rankSpecific, Comparator.reverseOrder())
            .thenComparing(RuleRow::orgSpecific, Comparator.reverseOrder())
            .thenComparingInt(RuleRow::priorityNo);

    /** 우선순위까지 같은 규칙이 겹쳤다는 표지. */
    private static final RuleRow DUPLICATE = new RuleRow(-1, -1, -1, 0, 0, null, null, null, null,
            false, false, false, false, 0);

    /** 조회한 규칙 한 줄. *Specific 은 그 조건이 NULL(공통)이 아니라 지정돼 있다는 뜻이다. */
    public record RuleRow(long ruleId, long policyVersionId, long itemId, int installmentFrom, int installmentTo,
                          BigDecimal ratePct, BigDecimal fixedAmount, String label, String policyVersion,
                          boolean offeringSpecific, boolean insurerSpecific, boolean rankSpecific, boolean orgSpecific,
                          int priorityNo) {
    }

    /** 한 규칙이 이기는 연속 회차 구간. */
    public record Run(RuleRow rule, int from, int to) {
    }

    /** runs 가 비어 있으면 excludeReason 에 제외 사유 코드가 있다. */
    public record Resolution(List<Run> runs, String excludeReason) {

        static Resolution excluded(String reason) {
            return new Resolution(List.of(), reason);
        }
    }

    private CommissionRuleResolver() {
    }

    public static Resolution resolve(List<RuleRow> rows, int lastInstallment) {
        if (rows.isEmpty()) {
            return Resolution.excluded("NO_COMMISSION_RULE");
        }
        if (rows.stream().map(RuleRow::policyVersionId).distinct().count() > 1) {
            return Resolution.excluded("POLICY_VERSION_AMBIGUOUS");
        }
        Map<Long, List<RuleRow>> byItem = rows.stream()
                .collect(Collectors.groupingBy(RuleRow::itemId, LinkedHashMap::new, Collectors.toList()));
        List<Run> runs = new ArrayList<>();
        for (List<RuleRow> itemRows : byItem.values()) {
            List<Run> itemRuns = resolveItem(itemRows, lastInstallment);
            if (itemRuns == null) {
                return Resolution.excluded("RULE_DUPLICATE");
            }
            runs.addAll(itemRuns);
        }
        if (runs.isEmpty()) {
            return Resolution.excluded("NO_COMMISSION_RULE");
        }
        runs.sort(Comparator.comparingInt(Run::from).thenComparingLong(run -> run.rule().itemId()));
        return new Resolution(List.copyOf(runs), null);
    }

    /** 한 수수료 항목의 회차별 승자를 연속 구간으로 묶는다. 우선순위까지 같은 규칙이 겹치는 회차가 있으면 null. */
    private static List<Run> resolveItem(List<RuleRow> itemRows, int lastInstallment) {
        List<Run> runs = new ArrayList<>();
        RuleRow current = null;
        int start = 0;
        for (int n = 1; n <= lastInstallment; n++) {
            RuleRow winner = winner(itemRows, n);
            if (winner == DUPLICATE) {
                return null;
            }
            if (current != null && (winner == null || winner.ruleId() != current.ruleId())) {
                runs.add(new Run(current, start, n - 1));
                current = null;
            }
            if (winner != null && current == null) {
                current = winner;
                start = n;
            }
        }
        if (current != null) {
            runs.add(new Run(current, start, lastInstallment));
        }
        return runs;
    }

    /** 이 회차에 적용되는 규칙. 없으면 null, 우선순위까지 같은 규칙이 둘 이상이면 중복 표지를 돌려준다. */
    private static RuleRow winner(List<RuleRow> itemRows, int installment) {
        List<RuleRow> applicable = itemRows.stream()
                .filter(r -> r.installmentFrom() <= installment && installment <= r.installmentTo())
                .sorted(ORDER).toList();
        if (applicable.isEmpty()) {
            return null;
        }
        if (applicable.size() > 1 && ORDER.compare(applicable.get(0), applicable.get(1)) == 0) {
            return DUPLICATE;
        }
        return applicable.get(0);
    }
}
