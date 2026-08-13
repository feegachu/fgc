package com.susukkang.fgc.reconciliation.adapter;

import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequestPort;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

/**
 * 설명 : 대사 실행 생성 트랜잭션의 커밋 이후 실행할 이벤트를 발행한다.
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@Component
@RequiredArgsConstructor
public class ReconciliationExecutionRequestAdapter implements ReconciliationExecutionRequestPort {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public void requestExecution(ReconciliationExecutionRequest request) {
        eventPublisher.publishEvent(request);
    }
}
