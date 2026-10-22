package com.susukkang.fgc.common.web;

import com.querydsl.core.types.Order;
import com.querydsl.core.types.OrderSpecifier;
import com.querydsl.core.types.dsl.ComparableExpressionBase;

import java.util.Map;

/**
 * QueryDSL 목록 조회의 정렬(#604). 화면 key 를 허용 목록의 표현식으로만 바꿔 ORDER BY 에 쓴다.
 * 값이 비어 있으면 방향과 관계없이 맨 뒤로 보내고, 동률은 호출한 쪽이 준 고정 기준으로 항상 같은 순서가 되게 한다.
 */
public final class QuerydslSort {

    private QuerydslSort() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public static OrderSpecifier<?>[] order(
            ListSort sort, Map<String, ? extends ComparableExpressionBase<?>> sortable, OrderSpecifier<?>... tieBreakers) {
        ComparableExpressionBase<?> column = sortable.get(sort.key());
        if (column == null) {
            return tieBreakers;
        }
        OrderSpecifier<?>[] result = new OrderSpecifier<?>[tieBreakers.length + 1];
        result[0] = new OrderSpecifier(sort.ascending() ? Order.ASC : Order.DESC, column,
                OrderSpecifier.NullHandling.NullsLast);
        System.arraycopy(tieBreakers, 0, result, 1, tieBreakers.length);
        return result;
    }
}
