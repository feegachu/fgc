package com.susukkang.fgc.validation.batch.contract;

/** Step 1 실행 생성 포트의 결과다. */
public record ValidationRunCreationResult(Long validationRunId) {
    public ValidationRunCreationResult {
        if (validationRunId == null || validationRunId <= 0) {
            throw new IllegalArgumentException("validationRunId는 1 이상이어야 합니다.");
        }
    }
}
