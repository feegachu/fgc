package com.susukkang.fgc.validation.batch.contract;

/** 원장 불균형은 허용 skip이 아니라 즉시 Step 실패로 처리한다. */
public class LedgerImbalanceDetectedException extends RuntimeException {
    public LedgerImbalanceDetectedException(long imbalanceCount) {
        super("원장 불균형 " + imbalanceCount + "건이 발견되었습니다.");
    }
}
