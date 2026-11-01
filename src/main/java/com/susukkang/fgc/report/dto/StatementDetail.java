package com.susukkang.fgc.report.dto;

import java.util.Map;

/** IF-API-88 응답 data. 목록과 같은 한 줄에 원천 ID·정책·계산 근거를 덧붙인다. */
public record StatementDetail(
        StatementEntry entry,
        Map<String, Object> evidence,
        StatementBasis basis
) {
}
