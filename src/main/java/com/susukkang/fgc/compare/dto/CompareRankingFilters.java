package com.susukkang.fgc.compare.dto;

import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * 설명 : 챗봇 세부 조건(모두 선택). 후보를 거를 뿐 평가 입력(기준 보험료 등)과 계산 규칙은 바꾸지 않는다.
 * 기준 보험료는 항상 서버가 본인 DB 에서 조회한다(FUN-079).
 * insurerIds 는 비교할 보험사(비어 있으면 전체), excludeCapOverflow 가 true 이면 1,200% 한도를 넘겨 이월이 생기는 상품을 순위에서 뺀다.
 */
public record CompareRankingFilters(
        @Size(max = 30) List<Long> insurerIds,
        Boolean excludeCapOverflow) {

    public static final CompareRankingFilters NONE = new CompareRankingFilters(List.of(), false);

    /** null 을 비어 있는 값으로 채운 정규화본. */
    public CompareRankingFilters normalized() {
        return new CompareRankingFilters(insurerIds == null ? List.of() : insurerIds.stream().distinct().sorted().toList(),
                Boolean.TRUE.equals(excludeCapOverflow));
    }

    public boolean hasInsurers() {
        return insurerIds != null && !insurerIds.isEmpty();
    }

    public boolean capOverflowExcluded() {
        return Boolean.TRUE.equals(excludeCapOverflow);
    }

    public boolean isEmpty() {
        return !hasInsurers() && !capOverflowExcluded();
    }
}
