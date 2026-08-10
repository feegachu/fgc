package com.susukkang.fgc.common.code;

/**
 * validation_run.run_type 의 자바측 표현 (V1 CHECK 제약과 동일해야 한다).
 * MONTHLY         : 월 통합검증(정기)
 * MANUAL_CONTRACT : 계약별 수동 검증(IF-API-33 등)
 * PRE_CONFIRM     : 지급 확정 직전 게이트 검증
 */
public enum ValidationRunType {
    MONTHLY,
    MANUAL_CONTRACT,
    PRE_CONFIRM
}
