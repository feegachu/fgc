package com.susukkang.fgc.contract.code;
/**
 * 설명 : PaymentCycleCode
 *  계약에서 월납주기가 어느정도 나타내는 데이터
 *  MONTHLY : 월납주기 , QUARTERLY : 분기납주기
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
public enum PaymentCycleCode {
    MONTHLY,
    QUARTERLY,
    SEMI_ANNUAL,
    ANNUAL,
    SINGLE,
    OTHER;

    public String label() {
        return switch (this) {
            case MONTHLY -> "월납";
            case QUARTERLY -> "3개월납";
            case SEMI_ANNUAL -> "6개월납";
            case ANNUAL -> "연납";
            case SINGLE -> "일시납";
            case OTHER -> "기타";
        };
    }
}
