package com.susukkang.fgc.validation.batch.contract;

/** Step 6b: 기표된 원장의 차변·대변 균형을 검사한다. */
public interface LedgerImbalanceCheckPort {
    LedgerImbalanceResult check(ValidationStepContext context);
}
