package com.susukkang.fgc.validation.service;

/**
 * MonthlyValidationJob이 validation_run.status/current_step을 갱신할 때 쓰는 전용 서비스
 */
public interface ValidationRunBatchProgressService {

    /**
     * 1) 실행생성 Step 성공 직후 호출. CREATED → RUNNING 전이 + current_step=1 + started_at=now()를 한 번에 반영
     *
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException 대상 실행이 없거나 이미 CREATED가 아닐 때
     */
    void startRunning(Long validationRunId);

    /**
     * 2)~8) 각 Step 성공 직후 호출. RUNNING 상태를 유지한 채 current_step만 그 Step 번호로 전진시킴
     *
     * @param step 방금 성공한 Step의 단계 번호(2~8)
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException 그 사이 RUNNING이 아니게 됐을 때
     */
    void advanceStep(Long validationRunId, int step);

    /**
     * ⑧예외생성까지 전부 성공한 뒤 Job 종료 시 호출. RUNNING → COMPLETED, current_step=8, completed_at=now() 반영
     *
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException 그 사이 RUNNING이 아니게 됐을 때
     */
    void completeRun(Long validationRunId);

    /**
     * 어떤 Step이든 실패하면 호출. RUNNING → FAILED로 전이하면서 실패한 Step 번호와 사유를 남김
     *
     * @param failedStep     실패한 Step의 단계 번호(1~8)
     * @param failureMessage 사람이 읽을 실패 사유(예외 메시지 요약)
     * @throws com.susukkang.fgc.common.exception.FgcBusinessException 그 사이 RUNNING이 아니게 됐을 때
     */
    void markFailed(Long validationRunId, int failedStep, String failureMessage);
}
