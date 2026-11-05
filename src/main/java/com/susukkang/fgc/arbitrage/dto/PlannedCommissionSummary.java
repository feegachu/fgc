package com.susukkang.fgc.arbitrage.dto;

import java.math.BigDecimal;
import java.util.List;

/** 미확인 연결이 있으면 remainingAmount는 검증된 잔액이 아닌 미차감 상한이다. */
public record PlannedCommissionSummary(BigDecimal remainingAmount, List<Long> unresolvedAttributionIds) {
    public PlannedCommissionSummary {
        unresolvedAttributionIds = List.copyOf(unresolvedAttributionIds);
    }
}
