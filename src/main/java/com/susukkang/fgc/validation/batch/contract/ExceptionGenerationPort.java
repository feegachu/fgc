package com.susukkang.fgc.validation.batch.contract;

/** Step 8: 검증 결과를 exception_case와 exception_action으로 멱등 생성한다. */
public interface ExceptionGenerationPort {
    StepProcessingResult generate(ValidationStepContext context);
}
