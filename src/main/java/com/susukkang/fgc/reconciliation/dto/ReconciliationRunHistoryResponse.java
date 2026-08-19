package com.susukkang.fgc.reconciliation.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * RECO-W01 "④ 실행 이력" 응답 1건(IF-API-39). 일부러 정적 from() 팩토리를 두지 않았다 —
 * matchRatePct 계산(대상 0건 처리 포함)과 라벨 변환이 이 이슈의 핵심 로직이라
 * ReconciliationRunHistoryServiceImpl에서 직접 조립하도록 남겨 둔다.
 *
 * validationRunId/reconciliationRunId는 VRUN-W02·RECO-W02·EXCP-W01·LEDG-W01로 이동할 때
 * 쓰는 식별자다(이슈의 "관련 화면으로 이동할 수 있도록 식별자 제공" 요구사항).
 */
public record ReconciliationRunHistoryResponse(
        Long reconciliationRunId,
        Long validationRunId,
        LocalDate settlementMonth,
        String paymentStage,
        String paymentStageLabel,
        Long insurerId,
        String insurerName,
        String status,
        String statusLabel,
        Long tolerancePolicyVersionId,
        String createdBy,
        OffsetDateTime createdAt,
        OffsetDateTime startedAt,
        OffsetDateTime completedAt,
        OffsetDateTime finalizedAt,
        String finalizedBy,
        long targetCount,
        long matchedCount,
        long exceptionCount,
        BigDecimal expectedTotal,
        BigDecimal actualTotal,
        BigDecimal differenceTotal,
        BigDecimal matchRatePct
) {
}
