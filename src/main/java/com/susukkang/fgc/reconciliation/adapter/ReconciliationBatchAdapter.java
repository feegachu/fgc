package com.susukkang.fgc.reconciliation.adapter;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.reconciliation.port.ReconciliationExecutionRequest;
import com.susukkang.fgc.reconciliation.service.ReconciliationBatchRunService;
import com.susukkang.fgc.reconciliation.service.ReconciliationExecutionCoordinator;
import com.susukkang.fgc.reconciliation.service.ReconciliationFailureRecorder;
import com.susukkang.fgc.validation.batch.contract.ContractSkip;
import com.susukkang.fgc.validation.batch.contract.ReconciliationBatchPort;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 설명 : MonthlyValidationJob Step 7의 실제 양방향 대사 어댑터
 *
 * @author hjKang
 * @since 2026-08-14
 * @version 1.2
 */
@Component
@RequiredArgsConstructor
public class ReconciliationBatchAdapter implements ReconciliationBatchPort {

    private final ReconciliationBatchRunService batchRunService;
    private final ReconciliationExecutionCoordinator executionCoordinator;
    private final ReconciliationFailureRecorder failureRecorder;

    @Override
    public StepProcessingResult reconcile(ValidationStepContext context, PaymentStage paymentStage) {
        long processedCount = 0;
        List<ContractSkip> skips = new ArrayList<>();
        var requests = batchRunService.prepare(context, paymentStage);
        for (int index = 0; index < requests.size(); index++) {
            try {
                processedCount += executionCoordinator.execute(requests.get(index));
            } catch (RuntimeException exception) {
                if (isSkippable(exception)) {
                    var request = requests.get(index);
                    List<Long> contractIds = batchRunService.findSelectedContractIds(
                            context.validationRunId(), request.insurerId());
                    if (contractIds.isEmpty()) {
                        throw exception;
                    }
                    for (Long contractId : contractIds) {
                        skips.add(new ContractSkip(
                                contractId,
                                "RECONCILIATION_FAILED",
                                message(exception)
                        ));
                    }
                    continue;
                }
                // 아직 실행하지 못한 RUNNING 행도 Step 중단 상태에 맞춰 FAILED로 종결합니다.
                for (int pending = index + 1; pending < requests.size(); pending++) {
                    try {
                        failureRecorder.record(requests.get(pending).reconciliationRunId());
                    } catch (RuntimeException cleanupException) {
                        // 원래 Step 실패를 보존하면서 가능한 나머지 실행의 종결은 계속 시도합니다.
                        exception.addSuppressed(cleanupException);
                    }
                }
                throw exception;
            }
        }
        return new StepProcessingResult(processedCount, skips.size(), 0, skips);
    }

    private static boolean isSkippable(RuntimeException exception) {
        return exception.getSuppressed().length == 0
                && (exception instanceof FgcBusinessException
                || exception instanceof DataIntegrityViolationException);
    }

    private static String message(RuntimeException exception) {
        return exception.getMessage() == null
                ? exception.getClass().getSimpleName()
                : exception.getMessage();
    }
}
