package com.susukkang.fgc.common.code;

/**
 * 설명 : 차익거래 검증 결과 tb_arbitrage_check의 result_status를 표현하는 enum class
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public enum ArbitrageCheckStatus {
    CLEAR("이상없음"),           // 차익거래 징후 없음
    CANDIDATE("검토대상"),       // 차익거래 검토대상
    REVIEW_REQUIRED("자료부족"); // 데이터 확인 필요

    private final String label;

    ArbitrageCheckStatus(String label) {
        this.label = label;
    }

    /** SIR-008: 코드값은 항상 한글 라벨과 함께 응답한다 — 화면이 아니라 서버가 라벨을 만든다. */
    public String label() {
        return label;
    }
}
