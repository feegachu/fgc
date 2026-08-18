package com.susukkang.fgc.reconciliation.service;

import com.susukkang.fgc.common.exception.FgcBusinessException;
import com.susukkang.fgc.common.exception.FgcErrorCode;
import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateResponse;
import com.susukkang.fgc.reconciliation.dto.ReconciliationExceptionBulkCreateRow;
import com.susukkang.fgc.reconciliation.dto.ReconciliationRunRow;
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
        ReconciliationRunRow reconciliationRun = reconciliationRunMapper.findById(reconciliationRunId);
        if (reconciliationRun == null) {
            throw new FgcBusinessException(
                    FgcErrorCode.COMMON_004, Map.of("id", reconciliationRunId));
        }
        // fgc.record_exception_detection()은 validation_run_id가 반드시 있어야 검증월을
        // 알 수 있다(V23_1) — 월 검증 실행과 연결되지 않은 단독 대사 실행은 여기서 막지
        // 않으면 Mapper의 INNER JOIN 때문에 "후보 0건"으로 조용히 넘어가 버린다.
        if (reconciliationRun.getValidationRunId() == null) {
            throw new FgcBusinessException(FgcErrorCode.RECO_003, Map.of());
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
