-- FUN-086 (#659) IF-API-74A: 설계사 계약 확인 기록의 멱등키·요청 해시·확인 시점 맥락 버전.
-- V58 의 agent_contract_check 를 재사용하고 컬럼만 덧붙인다. 기존 행은 없다(쓰는 코드가 이번에 처음 생김).
-- 확인 기록은 append-only 다. 최신 기록은 (checked_at, agent_contract_check_id) 내림차순으로 정한다.

ALTER TABLE fgc.agent_contract_check
    ADD COLUMN idempotency_key varchar(160),
    ADD COLUMN request_hash    varchar(64),
    ADD COLUMN context_version varchar(128),
    ADD COLUMN checked_event_seq integer NOT NULL DEFAULT 0 CHECK (checked_event_seq >= 0);

COMMENT ON COLUMN fgc.agent_contract_check.idempotency_key IS 'IF-API-74A Idempotency-Key. 같은 설계사의 같은 키는 한 행만 만든다';
COMMENT ON COLUMN fgc.agent_contract_check.request_hash    IS '요청 본문의 SHA-256. 같은 키에 다른 본문이 오면 409 로 구분한다';
COMMENT ON COLUMN fgc.agent_contract_check.checked_event_seq IS '확인 시점에 설계사가 본 마지막 상태 사건 순번. 이보다 큰 순번의 사건만 "확인 이후 새 사건"이다(접수일시는 소급 입력될 수 있어 쓰지 않는다)';
COMMENT ON COLUMN fgc.agent_contract_check.context_version IS '기록 시점에 설계사가 본 계약 맥락 버전(contract{id}-event{마지막 사건 순번})';

CREATE UNIQUE INDEX uq_agent_contract_check_idempotency
    ON fgc.agent_contract_check (agent_id, idempotency_key) WHERE idempotency_key IS NOT NULL;
CREATE INDEX ix_agent_contract_check_latest
    ON fgc.agent_contract_check (agent_id, contract_id, checked_at DESC, agent_contract_check_id DESC);

CREATE TRIGGER trg_agent_contract_check_append_only
BEFORE UPDATE OR DELETE ON fgc.agent_contract_check
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();
