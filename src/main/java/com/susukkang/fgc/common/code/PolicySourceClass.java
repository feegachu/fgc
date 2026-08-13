package com.susukkang.fgc.common.code;

/**
 * 정책버전 출처분류. policy_version.source_class CHECK
 * ('REGULATORY','INSURER_RULE','GA_POLICY','PROJECT_ASSUMPTION') 와 값이 같아야 한다.
 * PROJECT_ASSUMPTION 은 "근거가 없어 프로젝트가 가정한 값"이라 화면에서 반드시 구분 표기한다
 * (화면정의서 POL-W01 출처분류 배지).
 */
public enum PolicySourceClass {
    REGULATORY,
    INSURER_RULE,
    GA_POLICY,
    PROJECT_ASSUMPTION;

    /** SIR-008: 코드값은 항상 한글 라벨과 함께 응답한다 — 화면이 아니라 서버가 라벨을 만든다. */
    public String label() {
        return switch (this) {
            case REGULATORY -> "규제";
            case INSURER_RULE -> "보험사 규칙";
            case GA_POLICY -> "회사 정책";
            case PROJECT_ASSUMPTION -> "프로젝트 가정";
        };
    }
}
