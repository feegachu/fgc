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
    /**
     * 그 달을 대표해 확정(FINALIZED)할 수 있는 유형인가 (#494, 운영정책서 제44조).
     *
     * <p>MONTHLY·PRE_CONFIRM 은 MonthlyValidationJob 으로 월 전체를 다시 계산한다. MANUAL_CONTRACT 는
     * 일일 변경계약 배치·차익 재검증이 만드는 실행으로 변경된 계약만 계산하므로, 확정하면 일부 계약의 결과가
     * 그 달의 확정 결과로 잠긴다. 확정 조건 7("같은 검증월의 가장 최근 실행인가")이 세는 유형과 같은 집합이다
     * — {@code ValidationRunRepository#findFinalizeChecklistCounts} SQL 의 {@code run_type IN (...)} 과 함께 고친다.
     */
    public boolean isFinalizable() {
        return this == MONTHLY || this == PRE_CONFIRM;
    }

    public String label() {
        return switch (this) {
            case MONTHLY -> "월간";
            case MANUAL_CONTRACT -> "계약수동";
            case PRE_CONFIRM -> "확정전";
        };
    }
}
