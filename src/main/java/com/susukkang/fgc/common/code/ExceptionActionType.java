package com.susukkang.fgc.common.code;

/**
 * 설명 : 한도 예외 해결 시 사용하는 해결조치 유형
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public enum ExceptionActionType {
    CORRECT, // 정정
    REDUCE,  // 감액
    CANCEL,  // 취소
    DEFER    // 이연
}
