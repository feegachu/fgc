package com.susukkang.fgc.common.code;

/**
 * cap_check.check_kind 의 자바측 표현.
 * REALTIME     : 계약 등록·수정 시 즉시 계산
 * MANUAL       : 계약 상세에서 사용자가 요청한 수동 재검증
 * MONTHLY      : 월 1,200% 검증 배치 실행 시 재계산
 * PRE_CONFIRM  : 지급 확정 직전 게이트 판정
 */
public enum CapCheckKind {
    REALTIME,
    MANUAL,
    MONTHLY,
    PRE_CONFIRM
}
