package com.susukkang.fgc.common.web;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.JpaSort;

import java.util.List;
import java.util.Map;

/**
 * Spring Data JPQL {@code @Query} 목록의 컬럼별 정렬(#604).
 *
 * <p>화면 key 는 {@link ListSort} 가 허용 목록으로 걸러 낸 뒤, 코드에 박힌 JPQL 표현식(예: {@code o.organizationName})으로만
 * 바뀐다. 값이 비면 방향과 관계없이 맨 뒤로 보내고, 동률은 호출한 쪽의 고정 정렬(tieBreaker)을 따른다.
 */
public final class JpaListSort {

    private JpaListSort() {
    }

    /** sort 가 null 이면(요청 정렬 없음) 기존 기본 정렬(tieBreaker)만 쓴다. */
    public static Pageable pageable(int page, int size, ListSort sort, Map<String, String> sortable, Sort tieBreaker) {
        String expression = sort == null ? null : sortable.get(sort.key());
        if (expression == null) {
            return PageRequest.of(page - 1, size, tieBreaker);
        }
        Sort.Direction direction = sort.ascending() ? Sort.Direction.ASC : Sort.Direction.DESC;
        // unsafe: 코드에 박힌 별칭 포함 표현식을 그대로 쓴다. with(NULLS_LAST): 값이 비면 방향과 관계없이 맨 뒤로.
        List<Sort.Order> primary = JpaSort.unsafe(direction, expression).stream()
                .map(order -> order.with(Sort.NullHandling.NULLS_LAST))
                .toList();
        return PageRequest.of(page - 1, size, Sort.by(primary).and(tieBreaker));
    }
}
