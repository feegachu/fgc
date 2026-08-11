-- 2026-08-11 yslee - IF-API-25 지급 확정 멱등키를 거래 행에 저장
-- 기존 코드: 동일 확정 요청이 재전송되면 이미 CONFIRMED 상태라는 이유로 409를 반환
-- 문제: 네트워크 재시도 시 최초 성공 여부와 cap_check 결과를 안전하게 재조회할 수 없음
-- 개선: 성공한 확정 요청의 Idempotency-Key를 저장하고 전체 지급 건에서 중복 사용을 차단

ALTER TABLE fgc.commission_transaction
    ADD COLUMN confirm_idempotency_key varchar(160);

CREATE UNIQUE INDEX uq_commission_transaction_confirm_idempotency
    ON fgc.commission_transaction (confirm_idempotency_key)
    WHERE confirm_idempotency_key IS NOT NULL;

COMMENT ON COLUMN fgc.commission_transaction.confirm_idempotency_key IS
    'IF-API-25 확정 성공 요청의 선택 Idempotency-Key. 같은 키 재요청은 최초 확정 결과를 반환';
