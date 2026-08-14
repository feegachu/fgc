package com.susukkang.fgc.reconciliation.service;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * 설명 : FUN-050 대사 금액·날짜·회차 허용오차 판정 계약
 *
 * 비교 기준을 확정할 수 없는 null·복수값 입력은 엔진에서 먼저 검토 대상으로 분류한다.
 *
 * @author yslee
 * @since 2026-08-14
 * @version 1.2
 */
public interface TolerancePolicy {

    boolean matchesAmount(BigDecimal expectedAmount, BigDecimal actualAmount);

    boolean matchesDate(LocalDate expectedDate, LocalDate actualDate);

    boolean matchesInstallment(Integer expectedInstallment, Integer actualInstallment);
}
