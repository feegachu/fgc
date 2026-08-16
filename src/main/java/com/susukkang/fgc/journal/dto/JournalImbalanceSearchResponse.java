package com.susukkang.fgc.journal.dto;

import java.util.List;

/**
 * GET /api/v1/journals/imbalances 응답 — 검증 실행 범위의 불균형 분개 요약·목록.
 * totalCount는 LEDG-W01 경고 배너와 VRUN-W02 확정 조건(0건이어야 확정 가능, IF-API-37)이
 * 목록을 다시 세지 않고 바로 쓸 수 있도록 별도로 담는다.
 */
public record JournalImbalanceSearchResponse(
        long totalCount,
        List<JournalImbalanceItemResponse> content
) {
    public static JournalImbalanceSearchResponse of(List<LedgerImbalanceRow> rows, long totalCount) {
        List<JournalImbalanceItemResponse> content = rows.stream()
                .map(JournalImbalanceItemResponse::from)
                .toList();
        return new JournalImbalanceSearchResponse(totalCount, content);
    }
}
