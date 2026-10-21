package com.susukkang.fgc.common.code;

/**
 * 설명 : exception_case 테이블에서 사용하는 예외 유형
 *
 * @author hjKang
 * @version 1.0
 * @since 2026-08-12
 */
public enum ExceptionType {
    CAP_WARNING("1,200% 주의", "사용률 경고 기준 이상"),
    CAP_VIOLATION("1,200% 위반", "확정 위반 판정"),
    CAP_REVIEW_REQUIRED("1,200% 검토필요", "한도 판정 근거 확인 필요"),
    RECONCILIATION_MISMATCH("대사 불일치", "대사 금액 차이 확인 필요"),
    JOURNAL_IMBALANCE("원장 불균형", "차변·대변 합계 확인 필요"),
    JOURNAL_CORRECTION_REQUIRED("원장 정정 필요", "역분개·재기표 검토 필요"),
    ARBITRAGE_CANDIDATE("차익거래 검토대상", "위반 확정 아님"),
    REFUND_TABLE_MISSING("환급률표 없음", "적용할 환급률표 확인 필요"),
    PRODUCT_CODE_MISMATCH("상품코드 불일치", "상품코드 연결 확인 필요"),
    POLICY_MISSING("정책 없음", "적용 가능한 정책 확인 필요"),
    POLICY_DUPLICATE("정책 중복", "중복 적용 정책 확인 필요"),
    ALLOCATION_EVIDENCE_MISSING("배부 근거 없음", "배부 근거 보완 필요"),
    DATA_QUALITY("데이터 품질", "입력 데이터 확인 필요"),
    OTHER("기타", "상세 내용 확인 필요");

    private final String label;
    private final String summaryDescription;

    ExceptionType(String label, String summaryDescription) {
        this.label = label;
        this.summaryDescription = summaryDescription;
    }

    /** EXCP-W01 요약 카드에서 유형의 업무 의미를 설명한다. */
    public String summaryDescription() {
        return summaryDescription;
    }

    /** 화면정의서 EXCP-W01의 예외 유형 코드-표기 매핑과 일치시킨다. */
    public String label() {
        return label;
    }
}
