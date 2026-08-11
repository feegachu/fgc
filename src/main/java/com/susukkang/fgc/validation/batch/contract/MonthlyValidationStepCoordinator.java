package com.susukkang.fgc.validation.batch.contract;

import com.susukkang.fgc.common.code.PaymentStage;
import lombok.RequiredArgsConstructor;

/**
 * Step Tasklet이 도메인 포트를 호출할 때 사용하는 협업 규칙의 단일 진입점이다.
 * 실제 Spring Batch 배선은 후속 구현 이슈에서 각 포트 어댑터와 함께 연결한다.
 */
@RequiredArgsConstructor
public class MonthlyValidationStepCoordinator {

    private final ValidationRunCreationPort validationRunCreationPort;
    private final TargetSelectionPort targetSelectionPort;
    private final ScheduleRegenerationPort scheduleRegenerationPort;
    private final CapCheckBatchPort capCheckBatchPort;
    private final ArbitrageCheckBatchPort arbitrageCheckBatchPort;
    private final JournalPostingPort journalPostingPort;
    private final LedgerImbalanceCheckPort ledgerImbalanceCheckPort;
    private final ReconciliationBatchPort reconciliationBatchPort;
    private final ExceptionGenerationPort exceptionGenerationPort;

    public ValidationRunCreationResult createRun(ValidationJobContext context) {
        return validationRunCreationPort.create(context);
    }

    public StepProcessingResult selectTargets(ValidationStepContext context) {
        return targetSelectionPort.selectTargets(context);
    }

    public StepProcessingResult regenerateSchedules(ValidationStepContext context, long skipLimit) {
        StepProcessingResult result = scheduleRegenerationPort.regenerateSchedules(context);
        enforceSkipLimit("regenerateScheduleStep", result, skipLimit);
        return result;
    }

    public StepProcessingResult checkCaps(ValidationStepContext context, PaymentStage paymentStage, long skipLimit) {
        StepProcessingResult result = capCheckBatchPort.check(context, paymentStage);
        enforceSkipLimit("capCheckStep", result, skipLimit);
        return result;
    }

    public StepProcessingResult checkArbitrage(ValidationStepContext context, long skipLimit) {
        StepProcessingResult result = arbitrageCheckBatchPort.check(context);
        enforceSkipLimit("arbitrageCheckStep", result, skipLimit);
        return result;
    }

    public JournalPostingResult postJournals(ValidationStepContext context) {
        return journalPostingPort.post(context);
    }

    public LedgerImbalanceResult checkLedgerBalance(ValidationStepContext context) {
        LedgerImbalanceResult result = ledgerImbalanceCheckPort.check(context);
        if (result.imbalanceCount() > 0) {
            throw new LedgerImbalanceDetectedException(result.imbalanceCount());
        }
        return result;
    }

    public StepProcessingResult reconcile(ValidationStepContext context, PaymentStage paymentStage) {
        return reconciliationBatchPort.reconcile(context, paymentStage);
    }

    public StepProcessingResult generateExceptions(ValidationStepContext context) {
        return exceptionGenerationPort.generate(context);
    }

    private void enforceSkipLimit(String stepName, StepProcessingResult result, long skipLimit) {
        if (skipLimit < 0) {
            throw new IllegalArgumentException("skipLimit은 음수일 수 없습니다.");
        }
        if (result.skippedCount() > skipLimit) {
            throw new SkipLimitExceededException(stepName, result.skippedCount(), skipLimit);
        }
    }
}
