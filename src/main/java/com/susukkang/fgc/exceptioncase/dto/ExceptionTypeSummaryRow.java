package com.susukkang.fgc.exceptioncase.dto;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 유형별 미처리 예외 집계 DB 투영. */
public record ExceptionTypeSummaryRow(String exceptionType, long count) {
    /** 유형·심각도별 행을 유형별로 합친다. 행의 유형 순서를 유지한다. */
    public static List<ExceptionTypeSummaryRow> sumByType(List<ExceptionCountRow> rows) {
        Map<String, Long> byType = new LinkedHashMap<>();
        rows.forEach(row -> byType.merge(row.exceptionType(), row.count(), Long::sum));
        return byType.entrySet().stream()
                .map(entry -> new ExceptionTypeSummaryRow(entry.getKey(), entry.getValue()))
                .toList();
    }
}
