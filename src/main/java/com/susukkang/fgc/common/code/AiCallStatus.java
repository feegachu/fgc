package com.susukkang.fgc.common.code;

/**
 * ai_call_log.call_status CHECK 허용값(V58).
 * FALLBACK_RULE 은 외부 호출 없이 AI 를 건너뛴 경우(키 없음·차단 중·입력 초과)다 — 호출자는 규칙형 경로로 간다.
 */
public enum AiCallStatus {
    SUCCESS,
    ERROR,
    TIMEOUT,
    FALLBACK_RULE
}
