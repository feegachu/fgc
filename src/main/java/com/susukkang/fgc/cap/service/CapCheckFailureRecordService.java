package com.susukkang.fgc.cap.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 계약별 Cap 계산이 롤백된 뒤에도 실패 이력을 별도 트랜잭션으로 보존한다. */
@Service
@RequiredArgsConstructor
public class CapCheckFailureRecordService {

    private final ExceptionCaseMapper exceptionCaseMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(
            Long validationRunId,
            Long contractId,
            PaymentStage paymentStage,
            String description
    ) {
        exceptionCaseMapper.insertCapCheckFailure(
                validationRunId,
                contractId,
                paymentStage.name(),
                description
        );
    }
}
