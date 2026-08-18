package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateRow;
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

        // 후보 건수·생성 건수를 한 SQL 스냅샷에서 함께 받는다(코드리뷰 반영) — 두 번의
        // 별도 왕복으로 나누면 그 사이에 결과가 더 들어와 생성 건수가 후보 건수를
        // 넘어서는(skippedDuplicate 음수) 경쟁 상태가 생길 수 있었다.
        ReconciliationExceptionBulkCreateRow row =
                exceptionCaseMapper.bulkCreateFromReconciliationResultsByRun(reconciliationRunId);

        return new ReconciliationExceptionBulkCreateResponse(
                row.getCreatedCount(), row.getCandidateCount() - row.getCreatedCount());
    }
}
