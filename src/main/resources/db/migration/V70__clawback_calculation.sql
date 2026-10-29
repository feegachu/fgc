-- #669 FUN-055 환수액 계산판(IF-API-85).
-- 후보(V69)에 정책이 정해지는 계산 시점에 clawback_case를 만들고, 계산 결과는 판 단위로 쌓는다.
-- 과거 판은 수정·삭제하지 않는다. clawback_line은 case의 최신 판 결과만 담는다(인터페이스 §4-4-5).
SET search_path TO fgc, public;

ALTER TABLE clawback_case ADD COLUMN candidate_id bigint REFERENCES clawback_candidate(candidate_id);
CREATE UNIQUE INDEX uq_clawback_case_candidate ON clawback_case(candidate_id) WHERE candidate_id IS NOT NULL;

CREATE TABLE clawback_calculation (
    clawback_calculation_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    clawback_case_id        bigint        NOT NULL REFERENCES clawback_case(clawback_case_id),
    candidate_id            bigint        NOT NULL REFERENCES clawback_candidate(candidate_id),
    version_no              integer       NOT NULL CHECK (version_no > 0),
    source_version          varchar(128)  NOT NULL,
    policy_version_id       bigint        NOT NULL REFERENCES policy_version(policy_version_id),
    calc_rule_version       varchar(40)   NOT NULL,
    elapsed_months          integer       NOT NULL CHECK (elapsed_months >= 0),
    amount                  numeric(15,2) NOT NULL CHECK (amount >= 0),
    reason                  varchar(500)  NOT NULL CHECK (length(btrim(reason)) > 0),
    result_snapshot         jsonb         NOT NULL,
    calculated_by           bigint        NOT NULL REFERENCES app_user(user_id),
    calculated_at           timestamptz   NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_clawback_calculation_version UNIQUE (clawback_case_id, version_no),
    -- 같은 원천·정책·산식 판은 하나로 수렴한다(멱등키가 달라도 새 판을 만들지 않는다).
    CONSTRAINT uq_clawback_calculation_input UNIQUE (candidate_id, source_version, policy_version_id, calc_rule_version)
);
CREATE TRIGGER trg_clawback_calculation_append_only
BEFORE UPDATE OR DELETE ON clawback_calculation
FOR EACH ROW EXECUTE FUNCTION reject_update_delete();

CREATE TABLE clawback_calculation_request (
    idempotency_key         varchar(160) PRIMARY KEY,
    requested_by            bigint       NOT NULL REFERENCES app_user(user_id),
    candidate_id            bigint       NOT NULL REFERENCES clawback_candidate(candidate_id),
    request_hash            varchar(64)  NOT NULL,
    clawback_calculation_id bigint       NOT NULL REFERENCES clawback_calculation(clawback_calculation_id),
    requested_at            timestamptz  NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_clawback_calculation_request_calc ON clawback_calculation_request(clawback_calculation_id);
CREATE TRIGGER trg_clawback_calculation_request_append_only
BEFORE UPDATE OR DELETE ON clawback_calculation_request
FOR EACH ROW EXECUTE FUNCTION reject_update_delete();

COMMENT ON COLUMN clawback_case.candidate_id IS 'FUN-055 계산으로 만든 case의 환수 후보. 기존(legacy) case는 null';
COMMENT ON TABLE clawback_calculation IS 'FUN-055 환수 계산판. 판마다 원천 버전·정책·산식 버전·결과 snapshot을 보존하는 append-only 이력';
COMMENT ON TABLE clawback_calculation_request IS 'IF-API-85 멱등키·사용자·요청 해시와 결과 계산판. 계산과 같은 트랜잭션에 저장';
