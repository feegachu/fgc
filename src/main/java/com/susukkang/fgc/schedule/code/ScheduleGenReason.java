package com.susukkang.fgc.schedule.code;

/**
 * 설명 : 스케줄 헤더가 생성 되었을 때 사유를 모아둔 enum
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
public enum ScheduleGenReason {
    CONTRACT_CREATED, //계약 생성시 자동 사유
    POLICY_CHANGED //관리자 의해 스케줄을 재 생성 할 경우
}
