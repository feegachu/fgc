package com.susukkang.fgc.common.code;

/**
 * 설명 : exception_case 테이블에서 사용하는 예외 심각도
 * 참고 : 인터페이스 정의서 v2.0의 예외 심각도 상태값 대조표
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public enum ExceptionSeverity {
    INFO,       // 참고
    WARNING,    // 주의
    HIGH,       // 높음
    CRITICAL    // 긴급
}
