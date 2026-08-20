package com.susukkang.fgc.reconciliation.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * 설명 : FGC-FUN-048 대사 그룹 안의 수취인 짝짓기
 *
 * 한 계약·수수료항목·정산월 안에 수취인이 여러 명인 지급이 정상적으로 존재한다.
 * 관리자수수료(MANAGEMENT_COMMISSION)가 대표 사례로, 운영정책서 제20조 패턴 GA-LIFE-A 에 따라
 * 팀장 40% · 지사장 30% · 본부장 20% 세 명이 같은 항목·같은 1회차로 각각 수령한다.
 *
 * 그룹을 통째로 합산해 비교하면 두 가지 문제가 생긴다.
 *   - 수취인을 하나로 좁히지 못해 agentResolutionIssue 가 켜지고 REVIEW_REQUIRED 로 빠진다.
 *   - 팀장 몫이 지사장에게 지급돼도 합계가 같으면 일치로 통과한다.
 *
 * 그래서 묶기는 계약·항목·월로 하고, 그 안에서 수취인 단위로 짝을 지어 판정한다
 * (운영정책서 제36조 수취인 짝짓기 규칙).
 *
 * 수취인을 그룹 키(BaseMatchKey)에 넣지 않는 이유는 AGENT_MISMATCH 검출을 유지하기 위해서다.
 * 키에 넣어 갈라버리면 "팀장에게 갈 돈이 다른 사람에게 갔다"가 EXPECTED_MISSING + ACTUAL_MISSING
 * 두 건으로 흩어져 불일치 유형을 잃는다.
 *
 * @author hjKang
 * @since 2026-08-19
 * @version 1.0
 */
final class RecipientPairing {

    private RecipientPairing() {
    }

    /** 짝 하나가 대사 결과 한 행이 된다. */
    record Pair<E, A>(List<E> expected, List<A> actual) {
    }

    /**
     * 운영정책서 제36조 수취인 짝짓기 규칙을 적용한다.
     *
     * <pre>
     * 1) 양쪽에 같은 수취인이 있으면 짝을 만든다                  → 금액·회차 비교
     * 2) 남은 예상·실제가 각각 정확히 한 명씩이면 서로 짝짓는다   → AGENT_MISMATCH
     * 3) 그 외 남은 예상 수취인은 각각 단독                        → ACTUAL_MISSING
     * 4) 그 외 남은 실제 수취인은 각각 단독                        → EXPECTED_MISSING
     * 5) 양쪽 모두 두 명 이상 남으면 대응을 단정할 수 없어 한 덩어리로 남긴다 → REVIEW_REQUIRED
     * </pre>
     *
     * 수취인이 양쪽 모두 한 명 이하이면 나눌 것이 없으므로 입력을 그대로 한 짝으로 돌려준다.
     */
    static <E, A> List<Pair<E, A>> byRecipient(
            List<E> expectedSources,
            Function<E, Long> expectedRecipient,
            List<A> actualSources,
            Function<A, Long> actualRecipient
    ) {
        Map<Long, List<E>> expectedByRecipient = groupByRecipient(expectedSources, expectedRecipient);
        Map<Long, List<A>> actualByRecipient = groupByRecipient(actualSources, actualRecipient);

        if (expectedByRecipient.size() <= 1 && actualByRecipient.size() <= 1) {
            return List.of(new Pair<>(expectedSources, actualSources));
        }

        List<Pair<E, A>> pairs = new ArrayList<>();
        LinkedHashSet<Long> common = new LinkedHashSet<>(expectedByRecipient.keySet());
        common.retainAll(actualByRecipient.keySet());
        for (Long recipientId : common) {
            pairs.add(new Pair<>(expectedByRecipient.get(recipientId), actualByRecipient.get(recipientId)));
        }

        List<Long> expectedOnly = expectedByRecipient.keySet().stream()
                .filter(recipientId -> !common.contains(recipientId))
                .toList();
        List<Long> actualOnly = actualByRecipient.keySet().stream()
                .filter(recipientId -> !common.contains(recipientId))
                .toList();

        if (expectedOnly.isEmpty() && actualOnly.isEmpty()) {
            return List.copyOf(pairs);
        }
        if (expectedOnly.size() == 1 && actualOnly.size() == 1) {
            pairs.add(new Pair<>(
                    expectedByRecipient.get(expectedOnly.getFirst()),
                    actualByRecipient.get(actualOnly.getFirst())));
            return List.copyOf(pairs);
        }
        if (actualOnly.isEmpty()) {
            expectedOnly.forEach(recipientId ->
                    pairs.add(new Pair<>(expectedByRecipient.get(recipientId), List.of())));
            return List.copyOf(pairs);
        }
        if (expectedOnly.isEmpty()) {
            actualOnly.forEach(recipientId ->
                    pairs.add(new Pair<>(List.of(), actualByRecipient.get(recipientId))));
            return List.copyOf(pairs);
        }
        pairs.add(new Pair<>(
                expectedOnly.stream().flatMap(id -> expectedByRecipient.get(id).stream()).toList(),
                actualOnly.stream().flatMap(id -> actualByRecipient.get(id).stream()).toList()));
        return List.copyOf(pairs);
    }

    /** 수취인 식별값이 없는 행도 하나의 묶음으로 남긴다 — 판정은 기존 데이터 품질 규칙이 담당한다. */
    private static <T> Map<Long, List<T>> groupByRecipient(List<T> sources, Function<T, Long> recipient) {
        Map<Long, List<T>> grouped = new LinkedHashMap<>();
        for (T source : sources) {
            grouped.computeIfAbsent(recipient.apply(source), ignored -> new ArrayList<>()).add(source);
        }
        return grouped;
    }
}
