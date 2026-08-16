package com.susukkang.fgc.reconciliation.adapter;

import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.service.ReconciliationExecutionCoordinator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 설명 : reconciliation_run 커밋 뒤 실제 매칭과 저장을 시작한다.
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconciliationExecutionEventListener {

    private final ReconciliationExecutionCoordinator coordinator;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void executeAfterCommit(ReconciliationExecutionRequest request) {
        try {
            coordinator.execute(request);
        } catch (RuntimeException exception) {
            // 실행 생성 API의 커밋은 이미 완료되었습니다. 실패상태를 보존하고 요청 응답은 생성 결과로 유지합니다.
            log.error("대사 실행에 실패했습니다. reconciliationRunId={}", request.reconciliationRunId(), exception);
        }
    }
}
