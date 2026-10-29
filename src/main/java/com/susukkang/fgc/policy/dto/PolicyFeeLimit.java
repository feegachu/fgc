package com.susukkang.fgc.policy.dto;

import java.math.BigDecimal;

/** 승계 검증용 DB 한도 값. 초안 API에서 편집하지 않는 부속 데이터다. */
public record PolicyFeeLimit(String code, BigDecimal percentage, boolean includesIndirectExpense) {
    public PolicyFeeLimit {
        percentage = percentage.stripTrailingZeros();
    }
}
