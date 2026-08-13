package com.susukkang.fgc.common.code;

/**
 * 설명 : exception_case 테이블에서 사용하는 예외 유형
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public enum ExceptionType {
    CAP_WARNING,                 // 한도 사용률 주의
    CAP_VIOLATION,               // 한도 초과 위반
    CAP_REVIEW_REQUIRED,         // 한도 검토 필요
    RECONCILIATION_MISMATCH,     // 대사 결과 불일치
    JOURNAL_IMBALANCE,           // 회계 원장 차변·대변 불일치
    ARBITRAGE_CANDIDATE,         // 차익거래 의심 후보
    REFUND_TABLE_MISSING,        // 해약환급률표 누락
    PRODUCT_CODE_MISMATCH,       // 상품 코드 불일치
    POLICY_MISSING,              // 적용 가능한 정책 누락
    POLICY_DUPLICATE,            // 적용 가능한 정책 중복
    ALLOCATION_EVIDENCE_MISSING, // 배부 근거 누락
    DATA_QUALITY,                // 데이터 품질 오류
    OTHER                        // 기타 예외
}