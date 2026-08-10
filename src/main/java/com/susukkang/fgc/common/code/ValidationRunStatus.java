package com.susukkang.fgc.common.code;


public enum ValidationRunStatus {
    CREATED,
    RUNNING,
    COMPLETED,
    FAILED,
    FINALIZED;

    public boolean canTransitionTo(ValidationRunStatus target) {
        return switch (this){
            case CREATED -> target == RUNNING;
            case RUNNING -> target == COMPLETED || target == FAILED;
            case COMPLETED -> target == FINALIZED;
            case FAILED -> target == RUNNING;
            case FINALIZED -> false;
        };
    }

    /** 화면정의서 "월 통합검증 실행 상태" 코드-표기 매핑표(docs/FGC_화면정의서_v2_0.md)와 일치시킨다. */
    public String label() {
        return switch (this) {
            case CREATED -> "생성됨";
            case RUNNING -> "실행중";
            case COMPLETED -> "계산완료";
            case FAILED -> "실패";
            case FINALIZED -> "확정(잠김)";
        };
    }
}
