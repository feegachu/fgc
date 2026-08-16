package com.susukkang.fgc.validation.batch.contract;

import java.util.Objects;

/** validation_run 생성 이후 Step 2~8이 공통으로 받는 실행 컨텍스트다. */
public record ValidationStepContext(
        Long validationRunId,
        ValidationJobContext job
    ) {
    public ValidationStepContext {
        if (validationRunId == null || validationRunId <= 0) {
            throw new IllegalArgumentException("validationRunId는 1 이상이어야 합니다.");
        }
        Objects.requireNonNull(job, "job은 필수입니다.");
    }
}
