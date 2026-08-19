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
    CRITICAL;   // 긴급

    /** 인터페이스정의서 v2.0 부록(누락 정리 G)에서 화면 v2.0 신규 정의로 확정한 한글 라벨. */
    public String label() {
        return switch (this) {
            case INFO -> "참고";
            case WARNING -> "주의";
            case HIGH -> "높음";
            case CRITICAL -> "긴급";
        };
    }
}
