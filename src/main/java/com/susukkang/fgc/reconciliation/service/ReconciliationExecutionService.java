package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.dto.ReconciliationCandidate;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 설명 : 양방향 매칭·결과 저장·완료 전이를 한 트랜잭션으로 실행한다.
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class ReconciliationExecutionService {

    private final InsurerGaReconciliationMatcher insurerGaMatcher;
    private final GaFcReconciliationMatcher gaFcMatcher;
    private final ReconciliationResultPersistenceService persistenceService;
    private final ReconciliationRunLifecycleService lifecycleService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public long execute(ReconciliationExecutionRequest request) {
        List<? extends ReconciliationCandidate> candidates = switch (request.paymentStage()) {
            case INSURER_TO_GA -> insurerGaMatcher.match(request);
            case GA_TO_FC -> gaFcMatcher.match(request);
        };
        long processedCount = persistenceService.persist(request, candidates);
        lifecycleService.complete(request.reconciliationRunId());
        return processedCount;
    }
}
