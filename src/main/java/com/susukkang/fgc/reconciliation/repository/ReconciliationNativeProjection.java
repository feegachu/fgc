package com.susukkang.fgc.reconciliation.repository;

import org.hibernate.query.NativeQuery;
import org.springframework.beans.PropertyAccessorFactory;

import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

/**
 * 설명 : 명시한 순서·타입의 native scalar를 조회 DTO 속성에 매핑한다.
 *
 * @author C4t4ddict
 * @since 2026-10-05
 * @version 1.0
 */
final class ReconciliationNativeProjection {
    record Column(String property, Class<?> type) { }

    private ReconciliationNativeProjection() { }

    static <T> NativeQuery<T> map(NativeQuery<?> query, Supplier<T> factory, List<Column> columns) {
        for (Column column : columns) {
            query.addScalar(column.property().toLowerCase(Locale.ROOT), column.type());
        }
        return query.setTupleTransformer((values, aliases) -> {
            T row = factory.get();
            var properties = PropertyAccessorFactory.forBeanPropertyAccess(row);
            for (int i = 0; i < columns.size(); i++) {
                properties.setPropertyValue(columns.get(i).property(), values[i]);
            }
            return row;
        });
    }
}
