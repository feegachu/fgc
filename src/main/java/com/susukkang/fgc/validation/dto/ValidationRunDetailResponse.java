package com.susukkang.fgc.validation.dto;

import java.math.BigDecimal;
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
        ReconciliationSummary reconciliationSummary,
        ExceptionSummary exceptionSummary
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

    /**
     * ④-4 대사 — reconciliation_result 집계. matchedCount/mismatchedCount/unmatchedCount는
     * FGC-FUN-043 확대 요구사항인 MATCHED/MISMATCHED/UNMATCHED 3분류다(mismatchCount는
     * 기존 필드로 MATCHED가 아닌 전체를 뜻해 하위호환을 위해 남겨 둔다 — mismatchedCount
     * + unmatchedCount와 같아야 한다).
     */
    public record ReconciliationSummary(
            long resultCount,
            long mismatchCount,
            long matchedCount,
            long mismatchedCount,
            long unmatchedCount,
            BigDecimal differenceAmountTotal) {
    }

    /** SRC-032 실행별 검출과 중복 없는 관리자 업무건 집계. */
    public record ExceptionSummary(
            long detectedCount,
            long newCount,
            long recurringCount,
            long reopenedCount,
            long notDetectedCount,
            long openWorkItemCount) {
    }
}
