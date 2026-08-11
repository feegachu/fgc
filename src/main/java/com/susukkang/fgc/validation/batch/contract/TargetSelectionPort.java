package com.susukkang.fgc.validation.batch.contract;

/** Step 2: 계약·상품 버전·환급률표 스냅샷을 선별해 validation_target에 멱등 저장한다. */
public interface TargetSelectionPort {
    StepProcessingResult selectTargets(ValidationStepContext context);
}
