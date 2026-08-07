package com.susukkang.fgc.contract.domain;

/**
 * 설명 : DataOrigin
 *  계약 데이터의 원본 데이터의 종류를 나타내는 열거형 ,
 *  SEED : 시드값 , NORMALIZED_DB : 정규화된 데이터베이스 , MANUAL : 수동 입력
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-05
 */
public enum DataOrigin {
    SEED,           //
    NORMALIZED_DB,
    MANUAL
}
