package com.susukkang.fgc.validation.batch.contract;

import com.susukkang.fgc.common.code.PaymentStage;

/** Step 4: 지급단계별 1,200% 검증을 수행한다. 구현체는 cap_check 멱등키를 보장해야 한다. */
public interface CapCheckBatchPort {
    StepProcessingResult check(ValidationStepContext context, PaymentStage paymentStage);
}
