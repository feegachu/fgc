package com.susukkang.fgc.reconciliation.dto;

import lombok.Getter;
import lombok.Setter;

/** IF-API-42 — 대상 후보 건수와 실제 생성 건수를 한 SQL 스냅샷에서 함께 받는다. */
@Getter
@Setter
public class ReconciliationExceptionBulkCreateRow {
    private long candidateCount;
    private long createdCount;
}
