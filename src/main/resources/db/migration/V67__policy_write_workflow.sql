-- FUN-017 / #647: 정책 근거 상세와 작성 요청 멱등 결과.
-- 기존 정책·자식·승인 이력 테이블은 재사용한다. 기존 데이터는 변경하지 않는다.
CREATE TABLE fgc.policy_source_detail (
    policy_version_id bigint NOT NULL REFERENCES fgc.policy_version(policy_version_id),
    ref text NOT NULL,
    title varchar(200) NOT NULL,
    explanation varchar(2000),
    url varchar(500),
    PRIMARY KEY (policy_version_id, ref)
);

CREATE OR REPLACE FUNCTION fgc.guard_policy_source_detail_mutation()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
DECLARE
    v_old_id bigint;
    v_new_id bigint;
    v_policy record;
BEGIN
    IF TG_OP <> 'INSERT' THEN v_old_id := OLD.policy_version_id; END IF;
    IF TG_OP <> 'DELETE' THEN v_new_id := NEW.policy_version_id; END IF;
    FOR v_policy IN
        SELECT policy_version_id, status FROM fgc.policy_version
        WHERE policy_version_id = ANY(array_remove(ARRAY[v_old_id, v_new_id], NULL))
        ORDER BY policy_version_id FOR SHARE
    LOOP
        IF v_policy.status <> 'DRAFT' THEN
            RAISE EXCEPTION 'Policy source details can only be changed in DRAFT: %', v_policy.policy_version_id;
        END IF;
    END LOOP;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_policy_source_detail_draft_only
BEFORE INSERT OR UPDATE OR DELETE ON fgc.policy_source_detail
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_source_detail_mutation();

CREATE TABLE fgc.policy_write_request (
    idempotency_key varchar(160) PRIMARY KEY,
    requested_by bigint NOT NULL REFERENCES fgc.app_user(user_id),
    operation varchar(100) NOT NULL,
    request_hash varchar(64) NOT NULL,
    response_body jsonb NOT NULL,
    policy_version_id bigint NOT NULL REFERENCES fgc.policy_version(policy_version_id),
    requested_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX ix_policy_write_request_policy ON fgc.policy_write_request(policy_version_id);
CREATE TRIGGER trg_policy_write_request_append_only
BEFORE UPDATE OR DELETE ON fgc.policy_write_request
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

COMMENT ON TABLE fgc.policy_source_detail IS '정책버전의 근거 설명·링크. 초안에서만 편집하고 새 버전에 복사한다';
COMMENT ON TABLE fgc.policy_write_request IS '정책 작성 API의 전역 멱등키·사용자·작업·요청 해시·성공 응답. 업무 변경과 같은 트랜잭션에 저장';
