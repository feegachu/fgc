package com.susukkang.fgc.common.code;

/**
 * 설명 : ReasonCode
 * 계약상태 사건 TB에서 계약 상태 변경 사유 코드
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
public enum ReasonCode {
    NEW_CONTRACT, //신계약 성립
    LAPSE_BY_UNPAID, // 미납 지속으로 인한 효력 정지
    PREMIUM_UNPAID, //보험료 미납
    SURRENDER //해지
}
