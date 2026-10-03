package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.inOrder;

@ExtendWith(MockitoExtension.class)
class ValidationRunExceptionServiceImplTest {

    @Mock
    private ExceptionCaseRepository exceptionCaseRepository;

    @Test
    void generatesAllExceptionSourcesAndReturnsCreatedCount() {
        Long validationRunId = 118L;
        given(exceptionCaseRepository.insertFromCapChecks(validationRunId)).willReturn(2L);
        given(exceptionCaseRepository.insertFromArbitrageChecks(validationRunId)).willReturn(3L);
        given(exceptionCaseRepository.insertFromReconciliationResults(validationRunId)).willReturn(4L);
        given(exceptionCaseRepository.insertFromJournalImbalances(validationRunId)).willReturn(5L);

        StepProcessingResult result = new ValidationRunExceptionServiceImpl(exceptionCaseRepository)
                .generate(context(validationRunId));

        assertThat(result.processedCount()).isEqualTo(14L);
        assertThat(result.skippedCount()).isZero();
        assertThat(result.failureCount()).isZero();
        assertThat(result.skips()).isEmpty();

        InOrder calls = inOrder(exceptionCaseRepository);
        calls.verify(exceptionCaseRepository).insertFromCapChecks(validationRunId);
        calls.verify(exceptionCaseRepository).insertFromArbitrageChecks(validationRunId);
        calls.verify(exceptionCaseRepository).insertFromReconciliationResults(validationRunId);
        calls.verify(exceptionCaseRepository).insertFromJournalImbalances(validationRunId);
    }

    @Test
    void succeedsWithZeroWhenNoExceptionIsCreated() {
        Long validationRunId = 119L;

        StepProcessingResult result = new ValidationRunExceptionServiceImpl(exceptionCaseRepository)
                .generate(context(validationRunId));

        assertThat(result).isEqualTo(StepProcessingResult.success(0));
    }

    private ValidationStepContext context(Long validationRunId) {
        return new ValidationStepContext(
                validationRunId,
                new ValidationJobContext(
                        LocalDate.of(2026, 8, 1),
                        1L,
                        ValidationRunType.MONTHLY,
                        1L,
                        "req-exception-service"));
    }
}
