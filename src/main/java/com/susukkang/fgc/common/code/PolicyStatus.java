package com.susukkang.fgc.common.code;

/**
 * 정책버전 상태. policy_version.status CHECK
 * ('DRAFT','REVIEW','APPROVED','ACTIVE','RETIRED') 와 값이 같아야 한다.
 * 상태 전이는 DB 트리거(fgc.guard_policy_version_lifecycle)가 강제한다.
 */
public enum PolicyStatus {
    DRAFT,
    REVIEW,
    APPROVED,
    ACTIVE,
    RETIRED;

    /** SIR-008: 코드값은 항상 한글 라벨과 함께 응답한다 — 화면이 아니라 서버가 라벨을 만든다. */
    public String label() {
        return switch (this) {
            case DRAFT -> "작성중";
            case REVIEW -> "검토중";
            case APPROVED -> "승인";
            case ACTIVE -> "적용중";
            case RETIRED -> "종료";
        };
    }

    /** POL-W01 상태 배지 (목업 fgc-seed 매핑 그대로). */
    public String badgeClass() {
        return switch (this) {
            case DRAFT -> "fgc-badge--neutral";
            case REVIEW -> "fgc-badge--progress";
            case APPROVED -> "fgc-badge--warning";
            case ACTIVE -> "fgc-badge--normal";
            case RETIRED -> "fgc-badge--review";
        };
    }
}
