package com.susukkang.fgc.contract.domain;
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
    MONTHLY,  //매월
    QUARTERLY, //3개월마다
    SEMI_ANNUAL, //6개월마다
    ANNUAL, //12개월마다
    SINGLE, //일회납
    OTHER //기타
}
