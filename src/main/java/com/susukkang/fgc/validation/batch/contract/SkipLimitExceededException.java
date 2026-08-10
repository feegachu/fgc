package com.susukkang.fgc.validation.batch.contract;

/** 계약 단위 skip이 Step별 허용 한도를 초과했을 때 Step 실패로 전파한다. */
public class SkipLimitExceededException extends RuntimeException {
    public SkipLimitExceededException(String stepName, long skippedCount, long skipLimit) {
        super(stepName + "의 skip 건수 " + skippedCount + "건이 허용 한도 " + skipLimit + "건을 초과했습니다.");
    }
}
