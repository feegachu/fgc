package com.susukkang.fgc.contract.domain;

/**
 * 설명 : PremiumConversionRuleCode
 *  보험료 conversion 규칙 코드
 *  주어진 PaymentCycleCode에 따라 매핑되며 환산 규칙이 담겨있음
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
public enum PremiumConversionRuleCode {
    MONTHLY_AS_IS,
    QUARTERLY_DIV_3,
    SEMI_ANNUAL_DIV_6,
    ANNUAL_DIV_12,
}
