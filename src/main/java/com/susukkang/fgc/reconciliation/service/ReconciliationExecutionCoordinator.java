package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 설명 : 실행 실패 시 결과 트랜잭션 롤백 후 FAILED 상태를 별도로 확정한다.
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class ReconciliationExecutionCoordinator {

    private final ReconciliationExecutionService executionService;
    private final ReconciliationFailureRecorder failureRecorder;

    public long execute(ReconciliationExecutionRequest request) {
        try {
            return executionService.execute(request);
        } catch (RuntimeException exception) {
            try {
                failureRecorder.record(request.reconciliationRunId());
            } catch (RuntimeException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw exception;
        }
    }
}
