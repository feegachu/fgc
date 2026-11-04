-- FUN-028 (#523): 상태 사건으로 걸린 미확정 지급 건 보류. commission_transaction 상태에 HOLD 를 추가하지 않고 별도 행으로 보존한다.
-- 같은 지급 건·같은 사건은 1행(재처리가 같은 효과로 수렴). 해제(FUN-029)는 released_at·released_by_event_id 를 채우는 UPDATE 하나뿐이고
-- 그 밖의 변경·삭제는 DB 가 막는다. 지급 확정 API 가 같은 잠금 안에서 미해제 보류를 검사한다.

CREATE TABLE fgc.payment_hold (
    payment_hold_id          bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    commission_transaction_id bigint       NOT NULL REFERENCES fgc.commission_transaction(commission_transaction_id),
    contract_status_event_id bigint       NOT NULL REFERENCES fgc.contract_status_event(contract_status_event_id),
    policy_version_id        bigint       REFERENCES fgc.policy_version(policy_version_id),
    reason_code              varchar(80)  NOT NULL,
    created_at               timestamptz  NOT NULL DEFAULT clock_timestamp(),
    released_at              timestamptz,
    released_by_event_id     bigint       REFERENCES fgc.contract_status_event(contract_status_event_id),
    CONSTRAINT uq_payment_hold_transaction_event UNIQUE (commission_transaction_id, contract_status_event_id),
    CONSTRAINT ck_payment_hold_release CHECK ((released_at IS NULL) = (released_by_event_id IS NULL))
);

-- 미해제 보류 조회(지급 확정 게이트)
CREATE INDEX ix_payment_hold_open ON fgc.payment_hold (commission_transaction_id) WHERE released_at IS NULL;

CREATE FUNCTION fgc.guard_payment_hold_change()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = fgc, pg_temp
AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'payment_hold rows are append-only and cannot be deleted';
    END IF;
    -- 해제는 한 번만, 해제 외의 컬럼은 바꾸지 않는다.
    IF OLD.released_at IS NOT NULL
       OR NEW.commission_transaction_id <> OLD.commission_transaction_id
       OR NEW.contract_status_event_id <> OLD.contract_status_event_id
       OR NEW.policy_version_id IS DISTINCT FROM OLD.policy_version_id
       OR NEW.reason_code <> OLD.reason_code
       OR NEW.created_at <> OLD.created_at THEN
        RAISE EXCEPTION 'payment_hold allows only a single release update';
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_payment_hold_change
BEFORE UPDATE OR DELETE ON fgc.payment_hold
FOR EACH ROW EXECUTE FUNCTION fgc.guard_payment_hold_change();

COMMENT ON TABLE fgc.payment_hold IS
    '상태 사건(실효·해지·미납 등)으로 보류된 미확정 지급 건. 미해제 행이 1개 이상이면 지급 확정이 차단된다(FUN-028, 해제는 FUN-029)';
