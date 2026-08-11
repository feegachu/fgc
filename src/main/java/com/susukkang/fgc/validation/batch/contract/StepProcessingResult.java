package com.susukkang.fgc.validation.batch.contract;

import java.util.List;
import java.util.Objects;

/** Step별 처리·skip·실패 건수를 통일해 반환하는 결과다. 치명 오류는 결과가 아니라 예외로 전파한다. */
public record StepProcessingResult(
        long processedCount,
        long skippedCount,
        long failureCount,
        List<ContractSkip> skips
) {
    public StepProcessingResult {
        if (processedCount < 0 || skippedCount < 0 || failureCount < 0) {
            throw new IllegalArgumentException("처리 건수는 음수일 수 없습니다.");
        }
        skips = List.copyOf(Objects.requireNonNull(skips, "skips는 필수입니다."));
        if (skippedCount != skips.size()) {
            throw new IllegalArgumentException("skippedCount와 skips 크기가 일치해야 합니다.");
        }
    }

    public static StepProcessingResult success(long processedCount) {
        return new StepProcessingResult(processedCount, 0, 0, List.of());
    }
}
