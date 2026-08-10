package com.susukkang.fgc.validation.batch.contract;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.common.code.ValidationRunType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MonthlyValidationStepCoordinatorTest {

    @Mock private ValidationRunCreationPort validationRunCreationPort;
    @Mock private TargetSelectionPort targetSelectionPort;
    @Mock private ScheduleRegenerationPort scheduleRegenerationPort;
    @Mock private CapCheckBatchPort capCheckBatchPort;
    @Mock private ArbitrageCheckBatchPort arbitrageCheckBatchPort;
    @Mock private JournalPostingPort journalPostingPort;
    @Mock private LedgerImbalanceCheckPort ledgerImbalanceCheckPort;
    @Mock private ReconciliationBatchPort reconciliationBatchPort;
    @Mock private ExceptionGenerationPort exceptionGenerationPort;

    @Test
    void capCheckPassesPaymentStageAndFailsWhenSkipLimitIsExceeded() {
        MonthlyValidationStepCoordinator coordinator = coordinator();
        StepProcessingResult result = new StepProcessingResult(10, 2, 0,
                List.of(new ContractSkip(1L, "MISSING_DATA", "missing"),
                        new ContractSkip(2L, "MISSING_DATA", "missing")));
        when(capCheckBatchPort.check(stepContext(), PaymentStage.GA_TO_FC)).thenReturn(result);

        assertThatThrownBy(() -> coordinator.checkCaps(stepContext(), PaymentStage.GA_TO_FC, 1))
                .isInstanceOf(SkipLimitExceededException.class);

        verify(capCheckBatchPort).check(stepContext(), PaymentStage.GA_TO_FC);
    }

    @Test
    void ledgerImbalanceFailsTheStepImmediately() {
        MonthlyValidationStepCoordinator coordinator = coordinator();
        when(ledgerImbalanceCheckPort.check(stepContext())).thenReturn(new LedgerImbalanceResult(15, 1));

        assertThatThrownBy(() -> coordinator.checkLedgerBalance(stepContext()))
                .isInstanceOf(LedgerImbalanceDetectedException.class);

        verify(ledgerImbalanceCheckPort).check(stepContext());
    }

    @Test
    void reconciliationCallsThePortForEachPaymentStage() {
        MonthlyValidationStepCoordinator coordinator = coordinator();
        when(reconciliationBatchPort.reconcile(stepContext(), PaymentStage.INSURER_TO_GA))
                .thenReturn(StepProcessingResult.success(5));
        when(reconciliationBatchPort.reconcile(stepContext(), PaymentStage.GA_TO_FC))
                .thenReturn(StepProcessingResult.success(5));

        coordinator.reconcile(stepContext(), PaymentStage.INSURER_TO_GA);
        coordinator.reconcile(stepContext(), PaymentStage.GA_TO_FC);

        verify(reconciliationBatchPort).reconcile(stepContext(), PaymentStage.INSURER_TO_GA);
        verify(reconciliationBatchPort).reconcile(stepContext(), PaymentStage.GA_TO_FC);
    }

    @Test
    void delegatesTheRemainingStepContractsToTheirDomainPorts() {
        MonthlyValidationStepCoordinator coordinator = coordinator();
        ValidationJobContext jobContext = jobContext();
        ValidationRunCreationResult creationResult = new ValidationRunCreationResult(100L);
        StepProcessingResult result = StepProcessingResult.success(5);
        JournalPostingResult journalResult = new JournalPostingResult(5, 0);

        when(validationRunCreationPort.create(jobContext)).thenReturn(creationResult);
        when(targetSelectionPort.selectTargets(stepContext())).thenReturn(result);
        when(scheduleRegenerationPort.regenerateSchedules(stepContext())).thenReturn(result);
        when(arbitrageCheckBatchPort.check(stepContext())).thenReturn(result);
        when(journalPostingPort.post(stepContext())).thenReturn(journalResult);
        when(exceptionGenerationPort.generate(stepContext())).thenReturn(result);

        coordinator.createRun(jobContext);
        coordinator.selectTargets(stepContext());
        coordinator.regenerateSchedules(stepContext());
        coordinator.checkArbitrage(stepContext());
        coordinator.postJournals(stepContext());
        coordinator.generateExceptions(stepContext());

        verify(validationRunCreationPort).create(jobContext);
        verify(targetSelectionPort).selectTargets(stepContext());
        verify(scheduleRegenerationPort).regenerateSchedules(stepContext());
        verify(arbitrageCheckBatchPort).check(stepContext());
        verify(journalPostingPort).post(stepContext());
        verify(exceptionGenerationPort).generate(stepContext());
    }

    private MonthlyValidationStepCoordinator coordinator() {
        return new MonthlyValidationStepCoordinator(validationRunCreationPort, targetSelectionPort,
                scheduleRegenerationPort, capCheckBatchPort, arbitrageCheckBatchPort, journalPostingPort,
                ledgerImbalanceCheckPort, reconciliationBatchPort, exceptionGenerationPort);
    }

    private ValidationStepContext stepContext() {
        return new ValidationStepContext(100L, jobContext());
    }

    private ValidationJobContext jobContext() {
        return new ValidationJobContext(
                LocalDate.of(2026, 8, 1), 1L, ValidationRunType.MONTHLY, 12L, "request-1");
    }
}
