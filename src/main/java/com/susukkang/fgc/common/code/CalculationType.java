package com.susukkang.fgc.common.code;

/**
 * 설명 : 수수료 항목 테이블의 수수료 유형에 대한 enum
 *       수수료가 고정값으로 지급되는 방식과 월납보험료를 기준으로 환산되는 유형이 존재
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
public enum CalculationType {
    FIXED, //고정 금액 ( 정책지원금 , 고정 시책같은게 들어감)
    RATE //월납 보험료 기준 환산됨
}
