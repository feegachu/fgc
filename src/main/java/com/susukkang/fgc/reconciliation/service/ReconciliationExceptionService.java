package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateResponse;
import com.susukkang.fgc.reconciliation.mapper.ReconciliationRunMapper;
import com.susukkang.fgc.validation.mapper.ExceptionCaseMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

/** IF-API-42 RECO-W01 "불일치 예외 일괄 생성"(FUN-052). */
@Service
@RequiredArgsConstructor
public class ReconciliationExceptionService {

    private final ReconciliationRunMapper reconciliationRunMapper;
    private final ExceptionCaseMapper exceptionCaseMapper;

    @Transactional
    public ReconciliationExceptionBulkCreateResponse bulkCreate(Long reconciliationRunId) {
        if (reconciliationRunMapper.findById(reconciliationRunId) == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_004, Map.of("id", reconciliationRunId));
        }

        long candidates = exceptionCaseMapper.countReconciliationMismatchCandidates(reconciliationRunId);
        long created = exceptionCaseMapper.insertFromReconciliationResultsByRun(reconciliationRunId);

        return new ReconciliationExceptionBulkCreateResponse(created, candidates - created);
    }
}
