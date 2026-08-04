package com.susukkang.fgc.common.code;

/**
 * 지급단계. schedule_header / cap_rule_set / commission_transaction 등 여러 테이블의
 * payment_stage 컬럼과 값이 같아야 한다 (CHECK (payment_stage IN ('INSURER_TO_GA','GA_TO_FC'))).
 */
public enum PaymentStage {
    INSURER_TO_GA,
    GA_TO_FC
}
