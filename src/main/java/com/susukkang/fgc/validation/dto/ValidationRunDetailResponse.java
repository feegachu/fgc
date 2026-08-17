package com.susukkang.fgc.validation.dto;

import java.util.List;

/**
 * IF-API-47 검증 실행 상세 응답 — 헤더 + validation_target[] + 결과 요약 4블록 (VRUN-W02).
 * 요약은 결과 테이블을 매번 세서 만든다 — 화면용으로 결과를 복제 저장하지 않는다(FUN-043).
 */
public record ValidationRunDetailResponse(
        ValidationRunItemResponse header,
        List<ValidationTargetItemResponse> targets,
        TargetSummary targetSummary,
        CapSummary capSummary,
        ArbitrageSummary arbitrageSummary,
        LedgerSummary ledgerSummary,
        ReconciliationSummary reconciliationSummary
) {
    /** ③ 대상 선별 건수 (선정·제외·검토필요) */
    public record TargetSummary(long selectedCount, long excludedCount, long reviewRequiredCount) {
    }

    /** ④-1 1,200% — cap_check 집계 */
    public record CapSummary(long checkedCount, long violationCount, long warningCount, long reviewRequiredCount) {
    }

    /** ④-2 차익거래 — arbitrage_check 집계 */
    public record ArbitrageSummary(long checkedCount, long candidateCount, long reviewRequiredCount) {
    }

    /** ④-3 원장 — journal_header/vw_journal_imbalance 집계 */
    public record LedgerSummary(long journalCount, long imbalanceCount) {
    }

    /** ④-4 대사 — reconciliation_result 집계 */
    public record ReconciliationSummary(long resultCount, long mismatchCount) {
    }
}
