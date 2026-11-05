package com.susukkang.fgc.exceptioncase.dto;

import com.susukkang.fgc.common.code.ExceptionSeverity;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** IF-API-69 응답: 전체 건수와 심각도별(심각한 순)·유형별 건수. 세 합계는 같은 집계 행에서 나온다. */
public record ExceptionCountsResponse(
        long total,
        List<ExceptionSeverityCountResponse> bySeverity,
        List<ExceptionTypeSummaryResponse> byType
) {
    public static ExceptionCountsResponse from(List<ExceptionCountRow> rows) {
        Map<ExceptionSeverity, Long> severities = new EnumMap<>(ExceptionSeverity.class);
        rows.forEach(row -> severities.merge(ExceptionSeverity.valueOf(row.severity()), row.count(), Long::sum));
        List<ExceptionSeverityCountResponse> bySeverity = severities.entrySet().stream()
                .sorted(Map.Entry.<ExceptionSeverity, Long>comparingByKey().reversed())
                .map(entry -> new ExceptionSeverityCountResponse(entry.getKey(), entry.getValue()))
                .toList();
        return new ExceptionCountsResponse(
                rows.stream().mapToLong(ExceptionCountRow::count).sum(),
                bySeverity,
                ExceptionTypeSummaryRow.sumByType(rows).stream().map(ExceptionTypeSummaryResponse::from).toList());
    }
}
