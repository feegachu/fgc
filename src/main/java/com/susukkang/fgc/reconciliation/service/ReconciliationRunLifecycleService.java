package com.susukkang.fgc.reconciliation.service;

/**
 * 설명 : 대사 실행 상태 전이 서비스
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
public interface ReconciliationRunLifecycleService {

    void start(Long reconciliationRunId);

    void complete(Long reconciliationRunId);

    void fail(Long reconciliationRunId);
}
