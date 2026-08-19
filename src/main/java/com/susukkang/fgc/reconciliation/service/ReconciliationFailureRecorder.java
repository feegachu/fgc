package com.susukkang.fgc.reconciliation.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 설명 : 결과 저장 롤백과 분리해 치명적 대사 실패 상태를 기록한다.
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class ReconciliationFailureRecorder {

    private final ReconciliationRunLifecycleService lifecycleService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Long reconciliationRunId) {
        lifecycleService.fail(reconciliationRunId);
    }
}
