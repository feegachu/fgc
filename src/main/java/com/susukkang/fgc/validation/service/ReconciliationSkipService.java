package com.susukkang.fgc.validation.service;

import com.susukkang.fgc.common.code.PaymentStage;
import com.susukkang.fgc.exceptioncase.repository.ExceptionCaseRepository;
import com.susukkang.fgc.validation.batch.contract.ContractSkip;
import com.susukkang.fgc.validation.batch.contract.ValidationStepContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** 대사 결과가 없는 계약도 사유를 잃지 않도록 공통 검출 경로에 보존한다. */
@Service
@RequiredArgsConstructor
public class ReconciliationSkipService {
    private final ExceptionCaseRepository exceptionCaseRepository;

    @Transactional
    public void record(ValidationStepContext context, PaymentStage stage, List<ContractSkip> skips) {
        for (ContractSkip skip : skips) {
            Long recorded = exceptionCaseRepository.insertReconciliationSkip(context.validationRunId(),
                    skip.contractId(), stage.name(), skip.reasonCode(), skip.message());
            if (recorded == null) {
                throw new IllegalStateException("대사 skip 예외를 저장할 검증 실행이 없습니다.");
            }
        }
    }
}
