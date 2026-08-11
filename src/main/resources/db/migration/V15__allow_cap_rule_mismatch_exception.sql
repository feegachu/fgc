-- 2026-08-11 yslee - 확정 시 정책 버전 불일치 예외 유형을 DB 허용 목록에 반영
-- 기존 코드: 서비스는 CAP_RULE_MISMATCH를 저장하지만 exception_type CHECK에는 해당 값이 없음
-- 문제: 정책 불일치 확정 차단이 업무 오류가 아닌 DB 제약 오류로 바뀌어 500 응답이 발생
-- 개선: 기존 허용 유형을 보존하면서 CAP_RULE_MISMATCH를 명시적으로 추가
ALTER TABLE fgc.exception_case
    DROP CONSTRAINT IF EXISTS exception_case_exception_type_check;

ALTER TABLE fgc.exception_case
    ADD CONSTRAINT exception_case_exception_type_check
    CHECK (exception_type IN (
        'CAP_WARNING',
        'CAP_VIOLATION',
        'CAP_REVIEW_REQUIRED',
        'CAP_RULE_MISMATCH',
        'RECONCILIATION_MISMATCH',
        'JOURNAL_IMBALANCE',
        'ARBITRAGE_CANDIDATE',
        'REFUND_TABLE_MISSING',
        'PRODUCT_CODE_MISMATCH',
        'POLICY_MISSING',
        'POLICY_DUPLICATE',
        'ALLOCATION_EVIDENCE_MISSING',
        'DATA_QUALITY',
        'OTHER'
    ));
