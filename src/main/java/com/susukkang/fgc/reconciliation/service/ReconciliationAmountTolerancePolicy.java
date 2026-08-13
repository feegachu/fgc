package com.susukkang.fgc.reconciliation.service;

import java.math.BigDecimal;

/**
 * 설명 : FUN-050 허용오차 정책과 분리된 대사 금액 비교 계약
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
public interface ReconciliationAmountTolerancePolicy {

    boolean matches(BigDecimal expectedAmount, BigDecimal actualAmount);
}
