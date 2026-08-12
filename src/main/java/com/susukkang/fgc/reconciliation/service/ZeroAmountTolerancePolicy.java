package com.susukkang.fgc.reconciliation.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * 설명 : 운영정책서 제36조의 1차 금액 허용오차 0원 정책
 *
 * @author yslee
 * @since 2026-08-12
 * @version 1.2
 */
@Component
public class ZeroAmountTolerancePolicy implements ReconciliationAmountTolerancePolicy {

    @Override
    public boolean matches(BigDecimal expectedAmount, BigDecimal actualAmount) {
        return expectedAmount.compareTo(actualAmount) == 0;
    }
}
