package com.susukkang.fgc.common.code;

/**
 * validation_run.run_type 의 자바측 표현
 * MONTHLY         : 월 통합검증(정기)
 * MANUAL_CONTRACT : 계약별 수동 검증(IF-API-33 등)
 * PRE_CONFIRM     : 지급 확정 직전 게이트 검증
 */
public enum ValidationRunType {
    MONTHLY,
    MANUAL_CONTRACT,
    PRE_CONFIRM;

    /**
     * SIR-008: 코드값은 항상 한글 라벨과 함께 응답한다 — 화면이 아니라 서버가 라벨을 만든다.
     * 화면정의서 FGC-UI-VRUN-W01 목록 컬럼 정의(docs/FGC_화면정의서_v2_0.md)의 "실행 유형"
     * 표기(월간/계약수동/확정전)와 일치시킨다.
     */
    public String label() {
        return switch (this) {
            case MONTHLY -> "월간";
            case MANUAL_CONTRACT -> "계약수동";
            case PRE_CONFIRM -> "확정전";
        };
    }
}
