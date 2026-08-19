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
import java.util.Set;

/** IF-API-42 RECO-W01 "불일치 예외 일괄 생성"(FUN-052). */
@Service
@RequiredArgsConstructor
public class ReconciliationExceptionService {

    // COMPLETED·FINALIZED만 결과가 안정적이다 — CREATED/RUNNING은 아직 결과가 다 안
    // 채워졌을 수 있고, FAILED는 부분 결과라 예외로 확정 지으면 안 된다(코드리뷰 지적:
    // 지금까지는 실행 존재 여부만 확인해서 이 상태들에서도 부분 결과로 예외가 생성될
    // 수 있었다).
    private static final Set<String> ELIGIBLE_STATUSES = Set.of("COMPLETED", "FINALIZED");

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
        if (!ELIGIBLE_STATUSES.contains(reconciliationRun.getStatus())) {
            throw new FgcBusinessException(FgcErrorCode.RECO_004, Map.of());
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
