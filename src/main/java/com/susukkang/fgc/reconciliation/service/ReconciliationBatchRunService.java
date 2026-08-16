package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunInsertRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunMapper;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 설명 : Step 7에서 보험회사별 대사 실행을 생성하거나 실패 실행을 재개한다.
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@Service
@RequiredArgsConstructor
public class ReconciliationBatchRunService {

    private final ReconciliationRunMapper reconciliationRunMapper;
    private final ReconciliationRunLifecycleService lifecycleService;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<ReconciliationExecutionRequest> prepare(
            ValidationStepContext context,
            PaymentStage paymentStage
    ) {
        if (reconciliationRunMapper.lockValidationRun(context.validationRunId()) == null) {
            throw new IllegalStateException("FGC-FUN-041 검증 실행을 찾을 수 없습니다.");
        }
        List<ReconciliationExecutionRequest> requests = new ArrayList<>();
        for (Long insurerId : reconciliationRunMapper.findSelectedInsurerIds(context.validationRunId())) {
            ReconciliationRunRow run = reconciliationRunMapper.findByNaturalKey(
                    context.job().validationMonth(), paymentStage, insurerId, context.validationRunId());
            if (run == null) {
                ReconciliationRunInsertRow insertRow = new ReconciliationRunInsertRow();
                insertRow.setValidationRunId(context.validationRunId());
                insertRow.setSettlementMonth(context.job().validationMonth());
                insertRow.setPaymentStage(paymentStage);
                insertRow.setInsurerId(insurerId);
                insertRow.setCreatedBy(context.job().triggeredBy());
                reconciliationRunMapper.insert(insertRow);
                lifecycleService.start(insertRow.getReconciliationRunId());
                requests.add(request(context, paymentStage, insurerId, insertRow.getReconciliationRunId()));
                continue;
            }

            if ("CREATED".equals(run.getStatus()) || "FAILED".equals(run.getStatus())) {
                lifecycleService.start(run.getReconciliationRunId());
                requests.add(request(context, paymentStage, insurerId, run.getReconciliationRunId()));
            }
            // RUNNING은 다른 실행자가 소유한 것으로 보고 건너뜁니다. 명시적인 lease 만료 모델은 아직 없습니다.
            // COMPLETED·FINALIZED는 이미 확정된 결과를 다시 쓰지 않습니다.
        }
        return List.copyOf(requests);
    }

    @Transactional(readOnly = true)
    public List<Long> findSelectedContractIds(Long validationRunId, Long insurerId) {
        return List.copyOf(reconciliationRunMapper.findSelectedContractIds(validationRunId, insurerId));
    }

    private static ReconciliationExecutionRequest request(
            ValidationStepContext context,
            PaymentStage paymentStage,
            Long insurerId,
            Long reconciliationRunId
    ) {
        return new ReconciliationExecutionRequest(
                reconciliationRunId,
                context.validationRunId(),
                context.job().validationMonth(),
                paymentStage,
                insurerId,
                context.job().triggeredBy()
        );
    }
}
