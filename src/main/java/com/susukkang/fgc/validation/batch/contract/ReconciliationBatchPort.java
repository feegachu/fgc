package com.susukkang.fgc.validation.batch.contract;

import com.susukkang.fgc.common.code.PaymentStage;

/** Step 7: 지급단계별 양방향 대사를 수행한다. */
public interface ReconciliationBatchPort {
    StepProcessingResult reconcile(ValidationStepContext context, PaymentStage paymentStage);
}
