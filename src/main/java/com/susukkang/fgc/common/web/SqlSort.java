package com.susukkang.fgc.common.web;

import java.util.Map;

/**
 * 네이티브 SQL·JPQL 목록 조회의 ORDER BY 절(#604).
 *
 * <p>사용자 입력은 {@link ListSort} 가 허용 key 로 걸러 낸 뒤 이 클래스의 사전(key → 코드에 박힌 표현식)으로만 SQL 이 된다.
 * 값이 비어 있으면 방향과 관계없이 맨 뒤로 보내고, 동률은 고정 기준으로 항상 같은 순서가 되게 한다.
 */
public final class SqlSort {

    private SqlSort() {
    }

    /** 예: {@code ORDER BY ct.amount DESC NULLS LAST, ct.commission_transaction_id DESC} */
    public static String orderBy(ListSort sort, Map<String, String> sortable, String tieBreaker) {
        String expression = sortable.get(sort.key());
        if (expression == null) {
            return "ORDER BY " + tieBreaker;
        }
        return "ORDER BY " + expression + (sort.ascending() ? " ASC" : " DESC") + " NULLS LAST, " + tieBreaker;
    }
}
