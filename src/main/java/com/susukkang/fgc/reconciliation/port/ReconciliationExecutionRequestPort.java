package com.susukkang.fgc.reconciliation.port;

/**
 * 설명 : 커밋 이후 대사 실행을 시작하도록 요청하는 포트
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
public interface ReconciliationExecutionRequestPort {

    /**
     * 현재 트랜잭션이 커밋된 뒤 실행되도록 요청을 등록한다.
     * 구현체는 이 메서드 안에서 미커밋 reconciliation_run을 직접 조회하거나 계산하지 않는다.
     */
    void requestExecution(ReconciliationExecutionRequest request);
}
