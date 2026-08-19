package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 대사 실행 이력 1행. reconciliation_run + insurer(보험회사명) + app_user(생성자 login_id) +
 * vw_reconciliation_summary(대상/일치/불일치 건수·금액) 조인 투영.
 *
 * vw_reconciliation_summary는 reconciliation_run을 LEFT JOIN reconciliation_result로
 * 만들어서(V1__baseline_v2_1_2.sql:1490) 결과가 아직 없는 실행도 뷰에 항상 1행 존재하고
 * COUNT/SUM이 0으로 채워진다 — 그래서 이 Row도 INNER JOIN으로 그 뷰를 붙여도 결과 없는
 * 실행이 통째로 빠지는 일이 없다.
 */
@Getter
@Setter
public class ReconciliationRunHistoryRow {
    private Long reconciliationRunId;
    private Long validationRunId;
    private LocalDate settlementMonth;
    private String paymentStage;
    private Long insurerId;
    private String insurerName;
    private String status;
    private Long tolerancePolicyVersionId;
    private String createdBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private OffsetDateTime finalizedAt;
    private String finalizedBy;

    // vw_reconciliation_summary
    private Long targetCount;
    private Long matchedCount;
    private Long exceptionCount;
    private BigDecimal expectedTotal;
    private BigDecimal actualTotal;
    private BigDecimal differenceTotal;
}
