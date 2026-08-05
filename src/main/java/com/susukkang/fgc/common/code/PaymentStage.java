package com.susukkang.fgc.common.code;

/**
 * 지급단계. schedule_header / cap_rule_set / commission_transaction 등 여러 테이블의
 * payment_stage 컬럼과 값이 같아야 한다 (CHECK (payment_stage IN ('INSURER_TO_GA','GA_TO_FC'))).
 */
public enum PaymentStage {
    INSURER_TO_GA,
    GA_TO_FC;

    /** SIR-008: 코드값은 항상 한글 라벨과 함께 응답한다 — 화면이 아니라 서버가 라벨을 만든다. */
    public String label() {
        return switch (this) {
            case INSURER_TO_GA -> "원수사→GA";
            case GA_TO_FC -> "GA→설계사";
        };
    }
}
