package com.susukkang.fgc.common.code;

/**
 * 설명 : 정책버전 - 정책 유형에 관련 enum
 * 현행 , 4년 분급 , 7년 분급 , 1200 한도 , 환수 등 여러 정책 유형이 담겨 있다
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-10
 */
public enum PolicyType {
    CURRENT_COMMISSION, //현행
    FOUR_YEAR_COMMISSION, //4년분급
    SEVEN_YEAR_COMMISSION, //7년분급
    CAP_1200, //1200한도
    ALLOCATION,  //안분
    REFUND_RATE, //환수율 표
    CLAWBACK, //환수
    RECONCILIATION_TOLERANCE, //조정
    SCHEDULE_ELIGIBILITY; //월중 실효·부활 시 그 달 회차 지급 여부 (V2, v2.1.3 신규)

    /** SIR-008: 코드값은 항상 한글 라벨과 함께 응답한다 — 화면이 아니라 서버가 라벨을 만든다. */
    public String label() {
        return switch (this) {
            case CURRENT_COMMISSION -> "현행 수수료";
            case FOUR_YEAR_COMMISSION -> "4년 분급";
            case SEVEN_YEAR_COMMISSION -> "7년 분급";
            case CAP_1200 -> "1,200% 한도";
            case ALLOCATION -> "안분";
            case REFUND_RATE -> "예상 해약환급률표";
            case CLAWBACK -> "환수";
            case RECONCILIATION_TOLERANCE -> "대사 허용오차";
            case SCHEDULE_ELIGIBILITY -> "월중 상태 처리 기준";
        };
    }
}
