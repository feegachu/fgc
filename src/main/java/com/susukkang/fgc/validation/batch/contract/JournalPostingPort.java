package com.susukkang.fgc.validation.batch.contract;

/** Step 6a: 검증 원장을 기표한다. */
public interface JournalPostingPort {
    JournalPostingResult post(ValidationStepContext context);
}
