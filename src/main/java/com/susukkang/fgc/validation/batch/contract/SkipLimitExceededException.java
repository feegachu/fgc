package com.susukkang.fgc.validation.batch.contract;

import com.susukkang.fgc.common.code.PaymentStage;
import lombok.Getter;

/** 계약 단위 skip이 Step별 허용 한도를 초과했을 때 Step 실패로 전파한다. */
@Getter
public class SkipLimitExceededException extends RuntimeException {
    private final long skippedCount;
    private final long skipLimit;

    public SkipLimitExceededException(String stepName, PaymentStage paymentStage, long skippedCount, long skipLimit) {
        super(stepName + "의 " + paymentStage + " skip 건수 " + skippedCount + "건이 허용 한도 " + skipLimit + "건을 초과했습니다.");
        this.skippedCount = skippedCount;
        this.skipLimit = skipLimit;
    }

    /** regenerateSchedules·checkArbitrage처럼 지급단계 구분이 없는 Step용. */
    public SkipLimitExceededException(String stepName, long skippedCount, long skipLimit) {
        super(stepName + "의 skip 건수 " + skippedCount + "건이 허용 한도 " + skipLimit + "건을 초과했습니다.");
        this.skippedCount = skippedCount;
        this.skipLimit = skipLimit;
    }
}
