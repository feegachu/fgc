package com.susukkang.fgc.validation.batch.contract;

/** Step 5: 차익거래 검증 결과를 멱등 저장한다. 개별 대상 오류는 skip으로 반환할 수 있다. */
public interface ArbitrageCheckBatchPort {
    StepProcessingResult check(ValidationStepContext context);
}
