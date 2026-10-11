-- #389: 업무 기준일을 유지하며 초회보험료 정정 이력을 append한다.
ALTER TABLE fgc.contract_financial_snapshot
    ADD COLUMN snapshot_version integer NOT NULL DEFAULT 1,
    ADD COLUMN previous_snapshot_id bigint REFERENCES fgc.contract_financial_snapshot(contract_financial_snapshot_id),
    ADD CONSTRAINT ck_financial_snapshot_version_chain CHECK (
        (snapshot_version = 1 AND previous_snapshot_id IS NULL)
        OR (snapshot_version > 1 AND previous_snapshot_id IS NOT NULL)
    );

ALTER TABLE fgc.contract_financial_snapshot DROP CONSTRAINT uq_contract_financial_snapshot;
ALTER TABLE fgc.contract_financial_snapshot ADD CONSTRAINT uq_contract_financial_snapshot
    UNIQUE (contract_id, as_of_date, surrender_value_type, snapshot_version);

CREATE UNIQUE INDEX uq_financial_snapshot_successor
    ON fgc.contract_financial_snapshot(previous_snapshot_id) WHERE previous_snapshot_id IS NOT NULL;
CREATE INDEX ix_financial_snapshot_latest_version
    ON fgc.contract_financial_snapshot(contract_id, as_of_date DESC, surrender_value_type, snapshot_version DESC,
                                       contract_financial_snapshot_id DESC);

CREATE FUNCTION fgc.validate_financial_snapshot_predecessor() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND (
        NEW.snapshot_version IS DISTINCT FROM OLD.snapshot_version
        OR NEW.previous_snapshot_id IS DISTINCT FROM OLD.previous_snapshot_id
    ) THEN
        RAISE EXCEPTION 'Financial snapshot version links are immutable' USING ERRCODE = '23514';
    END IF;
    IF TG_OP <> 'INSERT' AND (
        OLD.snapshot_version > 1 OR EXISTS (
            SELECT 1 FROM fgc.contract_financial_snapshot successor
            WHERE successor.previous_snapshot_id = OLD.contract_financial_snapshot_id
        )
    ) AND (TG_OP = 'DELETE' OR NEW IS DISTINCT FROM OLD) THEN
        RAISE EXCEPTION 'Versioned financial snapshot history is append-only' USING ERRCODE = '23514';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    IF NEW.previous_snapshot_id IS NOT NULL AND NOT EXISTS (
        SELECT 1 FROM fgc.contract_financial_snapshot previous
        WHERE previous.contract_financial_snapshot_id = NEW.previous_snapshot_id
          AND previous.contract_id = NEW.contract_id
          AND previous.as_of_date = NEW.as_of_date
          AND previous.surrender_value_type = NEW.surrender_value_type
          AND previous.snapshot_version + 1 = NEW.snapshot_version
    ) THEN
        RAISE EXCEPTION 'Financial snapshot predecessor must have the same business key and preceding version'
            USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_financial_snapshot_predecessor
BEFORE INSERT OR UPDATE OR DELETE ON fgc.contract_financial_snapshot
FOR EACH ROW EXECUTE FUNCTION fgc.validate_financial_snapshot_predecessor();

COMMENT ON COLUMN fgc.contract_financial_snapshot.snapshot_version IS '동일 계약·기준일·환급금 유형의 버전. 기존/초기 행은 1';
COMMENT ON COLUMN fgc.contract_financial_snapshot.previous_snapshot_id IS '같은 업무키의 직전 버전 ID. 정정은 기존 행을 보존하고 새 행으로 추가';
