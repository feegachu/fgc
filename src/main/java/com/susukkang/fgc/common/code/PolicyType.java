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
    RECONCILIATION_TOLERANCE //조정
}
