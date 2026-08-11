package com.susukkang.fgc.validation.batch.contract;

import java.util.List;
import java.util.Objects;

/**
 * Step 6의 기표 결과다. StepProcessingResult와 계약을 통일해 실패 건수·skip 상세
 * (ContractSkip)까지 감사 로그와 예외 생성 단계(⑧)에 전달할 수 있게 한다 — 불균형 검사는
 * 여전히 별도 포트(LedgerImbalanceCheckPort)와 결과(LedgerImbalanceResult)로 분리되어 있다.
 */
public record JournalPostingResult(
        long postedJournalCount,
        long skippedCount,
        long failureCount,
        List<ContractSkip> skips
) {
    public JournalPostingResult {
        if (postedJournalCount < 0 || skippedCount < 0 || failureCount < 0) {
            throw new IllegalArgumentException("처리 건수는 음수일 수 없습니다.");
        }
        skips = List.copyOf(Objects.requireNonNull(skips, "skips는 필수입니다."));
        if (skippedCount != skips.size()) {
            throw new IllegalArgumentException("skippedCount와 skips 크기가 일치해야 합니다.");
        }
    }

    public static JournalPostingResult success(long postedJournalCount) {
        return new JournalPostingResult(postedJournalCount, 0, 0, List.of());
    }
}
