package com.susukkang.fgc.policy.code;

/**
 * 설명 : 분급 체계 판정 결과 상태
 */
public enum FeeRegimeResolutionStatus {
    SELECTED,          // 분급 룰셋을 하나 선택함
    NOT_APPLICABLE,    // 분급 미적용 — 호출부는 기존 현행 경로를 그대로 탄다
    REVIEW_REQUIRED    // 규정만으로 확정할 수 없음 — 자동 확정하지 않고 사유를 돌려준다
}
