-- 2026-08-11 yslee - 문서에 없는 수기 지급 순번과 중복 우회 인덱스 제거
-- 기존 코드: 같은 계약·설계사·항목·정산월도 source_sequence만 바꾸면 여러 건 저장 가능
-- 문제: 지급 건 중복을 막으려던 제약이 임의 순번으로 중복을 허용하고 API에 불필요한 입력을 강제
-- 개선: source_type+source_business_key 기존 UNIQUE를 단일 중복 기준으로 유지하고 순번 구조를 제거
DROP INDEX IF EXISTS fgc.uq_commission_payment_natural;

ALTER TABLE fgc.commission_transaction
    DROP COLUMN IF EXISTS source_sequence;
