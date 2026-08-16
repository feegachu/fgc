package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.ValidationRunType;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationJobContext;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
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
    private ExceptionCaseMapper exceptionCaseMapper;

    @Test
    void generatesAllExceptionSourcesAndReturnsCreatedCount() {
        Long validationRunId = 118L;
        given(exceptionCaseMapper.insertFromCapChecks(validationRunId)).willReturn(2L);
        given(exceptionCaseMapper.insertFromArbitrageChecks(validationRunId)).willReturn(3L);
        given(exceptionCaseMapper.insertFromReconciliationResults(validationRunId)).willReturn(4L);
        given(exceptionCaseMapper.insertFromJournalImbalances(validationRunId)).willReturn(5L);

        StepProcessingResult result = new ValidationRunExceptionServiceImpl(exceptionCaseMapper)
                .generate(context(validationRunId));

        assertThat(result.processedCount()).isEqualTo(14L);
        assertThat(result.skippedCount()).isZero();
        assertThat(result.failureCount()).isZero();
        assertThat(result.skips()).isEmpty();

        InOrder calls = inOrder(exceptionCaseMapper);
        calls.verify(exceptionCaseMapper).insertFromCapChecks(validationRunId);
        calls.verify(exceptionCaseMapper).insertFromArbitrageChecks(validationRunId);
        calls.verify(exceptionCaseMapper).insertFromReconciliationResults(validationRunId);
        calls.verify(exceptionCaseMapper).insertFromJournalImbalances(validationRunId);
    }

    @Test
    void succeedsWithZeroWhenNoExceptionIsCreated() {
        Long validationRunId = 119L;

        StepProcessingResult result = new ValidationRunExceptionServiceImpl(exceptionCaseMapper)
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
