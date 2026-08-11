package com.susukkang.fgc.validation.batch.contract;

/**
 * ValidationRunCompletionGate가 완료 조건 위반을 발견했을 때 던짐
 * 이 예외를 잡은 쪽(MonthlyValidationJobExecutionListener)은 조용히 넘기지 않고 validation_run을 FAILED로 전이
 */
public class ValidationRunNotCompletableException extends RuntimeException {
    public ValidationRunNotCompletableException(String reason) {
        super(reason);
    }
}
