package com.susukkang.fgc.contract.domain;

/**
 * 설명 : ContractStatus , 계약 상태 컬럼
 * APPLIED','ACTIVE','UNPAID','LAPSED','REVIVED','CANCELLED','TERMINATED','MATURED 의 상태가 존재
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
public enum ContractStatus {
    APPLIED,
    ACTIVE,
    UNPAID,
    LAPSED,
    REVIVED,
    CANCELLED,
    TERMINATED,
    MATURED;

    /** 화면과 API가 동일한 계약 상태 한글 라벨을 사용하도록 enum에서 관리한다. */
    public String label() {
        return switch (this) {
            case APPLIED -> "청약";
            case ACTIVE -> "정상";
            case UNPAID -> "미납";
            case LAPSED -> "실효";
            case REVIVED -> "부활";
            case CANCELLED -> "청약철회";
            case TERMINATED -> "해지";
            case MATURED -> "만기";
        };
    }
}
