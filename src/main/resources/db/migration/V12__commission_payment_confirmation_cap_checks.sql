-- 2026-08-11 yslee - 성공한 지급 확정 응답의 cap_check 식별자 스냅샷 저장
-- 기존 코드: 멱등 재요청에서 candidate_transaction_id에 연결된 과거 점검을 전부 다시 조회
-- 문제: 실패 후 DRAFT를 수정해 성공하면 이전 실패 점검까지 섞여 최초 성공 응답과 달라짐
-- 개선: CONFIRMED 전환과 함께 해당 성공 시도에서 생성한 cap_check ID 배열을 지급 원장에 저장

ALTER TABLE fgc.commission_transaction
    ADD COLUMN confirm_cap_check_ids bigint[] NOT NULL DEFAULT '{}';

COMMENT ON COLUMN fgc.commission_transaction.confirm_cap_check_ids IS
    'IF-API-25 성공 확정 응답의 capCheckIds 순서 보존 스냅샷';
