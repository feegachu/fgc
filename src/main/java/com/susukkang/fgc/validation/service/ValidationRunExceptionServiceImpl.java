package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.validation.batch.contract.ExceptionGenerationPort;
import com.susukkang.fgc.validation.batch.contract.StepProcessingResult;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
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
    private final ExceptionCaseMapper exceptionMapper;
    @Override
    @Transactional
    public StepProcessingResult generate(ValidationStepContext context) {
        Long validationRunId = context.validationRunId();

        long createdCount = 0;
        createdCount += exceptionMapper.insertFromCapChecks(validationRunId);
        createdCount += exceptionMapper.insertFromArbitrageChecks(validationRunId);
        createdCount += exceptionMapper.insertFromReconciliationResults(validationRunId);

        return StepProcessingResult.success(createdCount);
    }
}