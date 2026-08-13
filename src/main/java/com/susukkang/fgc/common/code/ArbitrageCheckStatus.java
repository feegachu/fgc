package com.susukkang.fgc.common.code;

/**
 * 설명 : 차익거래 검증 결과 tb_arbitrage_check의 result_status를 표현하는 enum class
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public enum ArbitrageCheckStatus {
    CLEAR,           // 차익거래 징후 없음
    CANDIDATE,       // 차익거래 검토대상
    REVIEW_REQUIRED  // 데이터 확인 필요
}