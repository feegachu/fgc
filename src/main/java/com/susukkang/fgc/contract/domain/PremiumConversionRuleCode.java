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
    MONTHLY_AS_IS,      //월 납입 x1
    MONTHLY_TO_QUARTERLY_X3, //분기별 납입 x3
    MONTHLY_TO_SEMI_ANNUAL_X6, //반년주기 납입 x6
    MONTHLY_TO_ANNUAL_X12, //연간 납입 x12
    DIRECT_INPUT //화면에서 원주기 보험료와 월납환산 보험료를 각각 직접 입력
}
