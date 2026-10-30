-- FUN-025·026 (#653·#625): 수기 상태 사건의 자유 사유, 계약 수정 멱등 결과, 사건 후속 작업과 영향 기록.
-- 기존 사건·처리 이력 테이블은 바꾸지 않고 덧붙인다(SRC-027 D-04). 기존 행은 수정하지 않는다.

ALTER TABLE fgc.contract_status_event ADD COLUMN reason_text varchar(2000);

COMMENT ON COLUMN fgc.contract_status_event.reason_text IS
    'IF-API-19 statusEvent.reason(자유 사유). 원천 참조 source_ref와 섞지 않는다. 이전 사건·정규화 사건은 NULL';

CREATE TABLE fgc.contract_write_request (
    idempotency_key varchar(160) PRIMARY KEY,
    requested_by    bigint       NOT NULL REFERENCES fgc.app_user(user_id),
    operation       varchar(100) NOT NULL,
    request_hash    varchar(64)  NOT NULL,
    response_body   jsonb        NOT NULL,
    contract_id     bigint       NOT NULL REFERENCES fgc.insurance_contract(contract_id),
    requested_at    timestamptz  NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_contract_write_request_contract ON fgc.contract_write_request(contract_id);
CREATE TRIGGER trg_contract_write_request_append_only
BEFORE UPDATE OR DELETE ON fgc.contract_write_request
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

COMMENT ON TABLE fgc.contract_write_request IS
    'IF-API-19 계약 수정의 멱등키·사용자·작업·요청 해시·성공 응답. 계약·사건 저장과 같은 트랜잭션에 남긴다';

-- 사건×처리기마다 1행. 처리기가 계약 잠금과 같은 트랜잭션에서 끝내므로 처리 중(lease) 상태를 따로 두지 않는다.
-- 성공·실패 이력은 기존 contract_status_event_processing에 덧붙인다(V6 append-only 유지).
CREATE TABLE fgc.contract_event_job (
    contract_status_event_id bigint       NOT NULL REFERENCES fgc.contract_status_event(contract_status_event_id),
    processing_job           varchar(100) NOT NULL,
    state                    varchar(20)  NOT NULL DEFAULT 'PENDING'
                             CHECK (state IN ('PENDING', 'SUCCEEDED', 'FAILED')),
    attempt_count            integer      NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_attempt_at          timestamptz,
    next_retry_at            timestamptz  NOT NULL DEFAULT clock_timestamp(),
    error_code               varchar(100),
    created_at               timestamptz  NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (contract_status_event_id, processing_job)
);
CREATE INDEX ix_contract_event_job_due ON fgc.contract_event_job(next_retry_at) WHERE state <> 'SUCCEEDED';

COMMENT ON TABLE fgc.contract_event_job IS
    '상태 사건 후속 작업(FUN-026). 계약별 사건순번 순서로 처리하고 실패하면 다음 재시도 시각을 둔다';

CREATE TABLE fgc.contract_status_event_effect (
    contract_status_event_effect_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_status_event_id bigint        NOT NULL REFERENCES fgc.contract_status_event(contract_status_event_id),
    processing_job           varchar(100)  NOT NULL,
    entity_type              varchar(20)   NOT NULL CHECK (entity_type IN ('PAYMENT', 'SCHEDULE_LINE')),
    entity_id                bigint        NOT NULL,
    previous_state           varchar(30),
    next_state               varchar(30)   NOT NULL,
    amount                   numeric(15,2),
    policy_version_id        bigint        REFERENCES fgc.policy_version(policy_version_id),
    reason_code              varchar(80)   NOT NULL,
    reason                   varchar(2000),
    created_at               timestamptz   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_contract_status_event_effect
        UNIQUE (contract_status_event_id, entity_type, entity_id, processing_job)
);
CREATE TRIGGER trg_contract_status_event_effect_append_only
BEFORE UPDATE OR DELETE ON fgc.contract_status_event_effect
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

COMMENT ON TABLE fgc.contract_status_event_effect IS
    '후속 작업이 지급 건·스케줄 회차에 낸 영향과 제외 사유(FUN-028·029 기록, IF-API-89 조회). 같은 사건·대상·작업은 1행';
