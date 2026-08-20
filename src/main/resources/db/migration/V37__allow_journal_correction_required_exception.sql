-- 2026-08-20 yslee - FUN-047 원장 정정 요청을 공통 예외 큐에서 처리하도록 유형 확장
-- 기존 코드: exception_case는 JOURNAL_IMBALANCE까지만 허용해 수동 정정 요청을 구분할 수 없음
-- 문제: 원장 화면의 정정 요청을 예외 업무건으로 저장하면 CHECK 제약 위반이 발생함
-- 개선: 기존 테이블과 예외 업무키는 유지하고 JOURNAL_CORRECTION_REQUIRED만 허용 목록에 추가
ALTER TABLE fgc.exception_case
    DROP CONSTRAINT IF EXISTS exception_case_exception_type_check;

ALTER TABLE fgc.exception_case
    ADD CONSTRAINT exception_case_exception_type_check
    CHECK (exception_type IN (
        'CAP_WARNING',
        'CAP_VIOLATION',
        'CAP_REVIEW_REQUIRED',
        'RECONCILIATION_MISMATCH',
        'JOURNAL_IMBALANCE',
        'JOURNAL_CORRECTION_REQUIRED',
        'ARBITRAGE_CANDIDATE',
        'REFUND_TABLE_MISSING',
        'PRODUCT_CODE_MISMATCH',
        'POLICY_MISSING',
        'POLICY_DUPLICATE',
        'ALLOCATION_EVIDENCE_MISSING',
        'DATA_QUALITY',
        'OTHER'
    ));
