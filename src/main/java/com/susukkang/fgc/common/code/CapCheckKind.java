package com.susukkang.fgc.common.code;

/**
 * cap_check.check_kind 의 자바측 표현.
 * REALTIME     : 계약 등록·수정 시 즉시 계산
 * MONTHLY      : 월 1,200% 검증 배치 실행 시 재계산
 * PRE_CONFIRM  : 지급 확정 직전 게이트 판정
 */
public enum CapCheckKind {
    REALTIME,
    MONTHLY,
    PRE_CONFIRM
}
