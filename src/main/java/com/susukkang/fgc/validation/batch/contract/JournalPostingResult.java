package com.susukkang.fgc.validation.batch.contract;

/** Step 6의 기표 결과다. 불균형 검사는 별도 포트와 결과로 분리한다. */
public record JournalPostingResult(long postedJournalCount, long skippedCount) {
    public JournalPostingResult {
        if (postedJournalCount < 0 || skippedCount < 0) {
            throw new IllegalArgumentException("처리 건수는 음수일 수 없습니다.");
        }
    }
}
