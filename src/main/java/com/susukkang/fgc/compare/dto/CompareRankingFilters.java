package com.susukkang.fgc.compare.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 설명 : 챗봇 세부 조건(모두 선택). 후보를 거르거나 기준 보험료를 바꿀 뿐 계산 규칙은 바꾸지 않는다.
 * insurerIds 는 비교할 보험사(비어 있으면 전체), monthlyPremium 은 순위 계산에 쓸 기준 월 보험료(없으면 본인 평균),
 * excludeCapOverflow 가 true 이면 1,200% 한도를 넘겨 이월이 생기는 상품을 순위에서 뺀다.
 */
public record CompareRankingFilters(
        @Size(max = 30) List<Long> insurerIds,
        @Positive @Max(100_000_000L) Long monthlyPremium,
        Boolean excludeCapOverflow) {

    public static final CompareRankingFilters NONE = new CompareRankingFilters(List.of(), null, false);

    /** null 을 비어 있는 값으로 채운 정규화본. */
    public CompareRankingFilters normalized() {
        return new CompareRankingFilters(insurerIds == null ? List.of() : insurerIds.stream().distinct().sorted().toList(),
                monthlyPremium, Boolean.TRUE.equals(excludeCapOverflow));
    }

    public boolean hasInsurers() {
        return insurerIds != null && !insurerIds.isEmpty();
    }

    public boolean capOverflowExcluded() {
        return Boolean.TRUE.equals(excludeCapOverflow);
    }

    public boolean isEmpty() {
        return !hasInsurers() && monthlyPremium == null && !capOverflowExcluded();
    }
}
