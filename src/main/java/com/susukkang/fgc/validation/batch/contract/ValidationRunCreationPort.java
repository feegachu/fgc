package com.susukkang.fgc.validation.batch.contract;

/** Step 1: JobParameter로 validation_run을 생성하고 멱등한 실행 식별자를 반환한다. */
public interface ValidationRunCreationPort {
    ValidationRunCreationResult create(ValidationJobContext context);
}
