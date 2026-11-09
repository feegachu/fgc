package com.susukkang.fgc.exceptioncase.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import com.susukkang.fgc.common.code.ExceptionSeverity;

/** 유형별 미처리 예외 집계 DB 투영. */
public record ExceptionTypeSummaryRow(String exceptionType, long count) {
    /** 유형별로 합치고, 각 유형의 가장 높은 심각도부터 정렬한다. 동순위는 유형 코드순이다. */
    public static List<ExceptionTypeSummaryRow> sumByType(List<ExceptionCountRow> rows) {
        Map<String, Long> byType = new LinkedHashMap<>();
        Map<String, Integer> highestSeverity = new LinkedHashMap<>();
        rows.forEach(row -> {
            byType.merge(row.exceptionType(), row.count(), Long::sum);
            highestSeverity.merge(row.exceptionType(), ExceptionSeverity.valueOf(row.severity()).ordinal(), Math::max);
        });
        return byType.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Long>>comparingInt(entry -> highestSeverity.get(entry.getKey())).reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> new ExceptionTypeSummaryRow(entry.getKey(), entry.getValue()))
                .toList();
    }
}
