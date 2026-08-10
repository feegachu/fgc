package com.susukkang.fgc.validation.batch.contract;

/** Step 6의 차변·대변 균형검사 결과다. imbalanceCount가 1 이상이면 Step을 실패시킨다. */
public record LedgerImbalanceResult(long inspectedJournalCount, long imbalanceCount) {
    public LedgerImbalanceResult {
        if (inspectedJournalCount < 0 || imbalanceCount < 0) {
            throw new IllegalArgumentException("처리 건수는 음수일 수 없습니다.");
        }
    }
}
