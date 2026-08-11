package com.susukkang.fgc.validation.batch.contract;

/**
 * 완료 조건은 이번 실행이 만들어 낸 결과 전체를 훑어봐야 판단할 수 있는 "집계 조건"이기 때문에
 * Job이 COMPLETED로 넘어가기 직전에 반드시 거치는 마지막 문지기를 별도 Port로 분리
 */
public interface ValidationRunCompletionGate {

    /**
     * 완료 조건을 만족하지 못하면 ValidationRunNotCompletableException을 던진다.
     * 정상 반환(void)이면 COMPLETED로 전이해도 된다는 뜻이다.
     */
    void verifyCompletable(ValidationStepContext context);
}
