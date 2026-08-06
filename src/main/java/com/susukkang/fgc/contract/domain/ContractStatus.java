package com.susukkang.fgc.contract.domain;

/**
 * 설명 : ContractStatus , 계약 상태 컬럼
 * APPLIED','ACTIVE','UNPAID','LAPSED','REVIVED','CANCELLED','TERMINATED','MATURED 의 상태가 존재
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
public enum ContractStatus {
    APPLIED, //청약
    ACTIVE, //유지
    UNPAID, //미납
    LAPSED, //실효
    REVIVED, //부활
    CANCELLED, //청약 철회
    TERMINATED, //해지
    MATURED //만기
}
