package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.validation.batch.contract.ExceptionGenerationPort;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;


/**
 * 설명 : ValidationRunExceptionServiceImpl
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-14
 */
@Service
@RequiredArgsConstructor
public class ValidationRunExceptionServiceImpl implements ExceptionGenerationPort {
    private final ExceptionCaseRepository exceptionCaseRepository;
    @Override
    @Transactional
    public StepProcessingResult generate(ValidationStepContext context) {
        Long validationRunId = context.validationRunId();

        long createdCount = 0;
        createdCount += exceptionCaseRepository.insertFromCapChecks(validationRunId);
        createdCount += exceptionCaseRepository.insertFromArbitrageChecks(validationRunId);
        createdCount += exceptionCaseRepository.insertFromReconciliationResults(validationRunId);
        createdCount += exceptionCaseRepository.insertFromJournalImbalances(validationRunId);

        return StepProcessingResult.success(createdCount);
    }
}
