package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 설명 : reconciliation_run 단건 조회 결과
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Getter
@Setter
public class ReconciliationRunRow {

    private Long reconciliationRunId;
    private Long validationRunId;
    private LocalDate settlementMonth;
    private String paymentStage;
    private Long insurerId;
    private String status;
    private OffsetDateTime startedAt;
    private OffsetDateTime completedAt;
    private Long createdBy;
    private OffsetDateTime createdAt;
}
