package com.susukkang.fgc.reconciliation.dto;

import java.time.LocalDate;

/**
 * 대사 실행 이력 검색조건(IF-API-39, RECO-W01 "④ 실행 이력"). settlementMonth/paymentStage가
 * null이면 그 조건은 걸지 않는다(전체). settlementMonth는 그 달의 1일(reconciliation_run.
 * settlement_month와 같은 CHECK(fgc.is_first_day_of_month)를 만족해야 한다).
 */
public record ReconciliationRunSearchCriteria(
        LocalDate settlementMonth,
        String paymentStage
) {
}
