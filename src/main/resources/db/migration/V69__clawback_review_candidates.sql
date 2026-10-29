-- FUN-054 (#668): 정책 없는 원천도 저장한다. case/계산/통지 이력은 변경하지 않는다.
CREATE TABLE fgc.clawback_candidate (
    candidate_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_id bigint NOT NULL REFERENCES fgc.insurance_contract(contract_id),
    trigger_type varchar(20) NOT NULL CHECK (trigger_type IN ('STATUS_EVENT', 'ARBITRAGE')),
    status_event_id bigint REFERENCES fgc.contract_status_event(contract_status_event_id),
    arbitrage_check_id bigint REFERENCES fgc.arbitrage_check(arbitrage_check_id),
    source_id bigint GENERATED ALWAYS AS (COALESCE(status_event_id, arbitrage_check_id)) STORED,
    source_version varchar(128) NOT NULL,
    event_date date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_clawback_candidate_source CHECK (
        (trigger_type = 'STATUS_EVENT' AND status_event_id IS NOT NULL AND arbitrage_check_id IS NULL)
        OR (trigger_type = 'ARBITRAGE' AND arbitrage_check_id IS NOT NULL AND status_event_id IS NULL)
    ),
    CONSTRAINT uq_clawback_candidate_source UNIQUE (trigger_type, source_id, contract_id),
    CONSTRAINT uq_clawback_candidate_event UNIQUE (status_event_id),
    CONSTRAINT uq_clawback_candidate_arbitrage UNIQUE (arbitrage_check_id)
);
CREATE INDEX ix_clawback_candidate_event_date ON fgc.clawback_candidate (event_date DESC, candidate_id DESC);
CREATE INDEX ix_clawback_candidate_contract ON fgc.clawback_candidate (contract_id, candidate_id);
COMMENT ON TABLE fgc.clawback_candidate IS 'FUN-054 환수 검토 원천. 정책/계산이 없어도 보존하며 조회로 생성하지 않는다';
COMMENT ON COLUMN fgc.clawback_candidate.source_version IS '관측한 원천행의 fingerprint. API sourceVersion은 현재 지급/스케줄 근거까지 포함';

CREATE FUNCTION fgc.guard_clawback_candidate_source() RETURNS trigger LANGUAGE plpgsql SET search_path = fgc, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND ROW(NEW.contract_id, NEW.trigger_type, NEW.status_event_id, NEW.arbitrage_check_id)
        IS DISTINCT FROM ROW(OLD.contract_id, OLD.trigger_type, OLD.status_event_id, OLD.arbitrage_check_id) THEN
        RAISE EXCEPTION 'Clawback candidate identity is immutable';
    END IF;
    IF NEW.trigger_type = 'STATUS_EVENT' THEN
        IF NOT EXISTS (SELECT 1 FROM fgc.contract_status_event e
            WHERE e.contract_status_event_id = NEW.status_event_id AND e.contract_id = NEW.contract_id
              AND e.new_status IN ('TERMINATED', 'LAPSED', 'CANCELLED')
              AND (e.effective_at AT TIME ZONE 'Asia/Seoul')::date = NEW.event_date) THEN
            RAISE EXCEPTION 'Clawback candidate must reference its actual contract status event';
        END IF;
    ELSE
        IF NOT EXISTS (SELECT 1 FROM fgc.arbitrage_check a
            WHERE a.arbitrage_check_id = NEW.arbitrage_check_id AND a.contract_id = NEW.contract_id
              AND a.payment_stage = 'GA_TO_FC' AND a.as_of_date = NEW.event_date) THEN
            RAISE EXCEPTION 'Clawback candidate must reference its actual GA_TO_FC arbitrage result';
        END IF;
    END IF;
    RETURN NEW;
END;
$$;
CREATE TRIGGER trg_clawback_candidate_source BEFORE INSERT OR UPDATE ON fgc.clawback_candidate
    FOR EACH ROW EXECUTE FUNCTION fgc.guard_clawback_candidate_source();
CREATE TRIGGER trg_clawback_candidate_updated_at BEFORE UPDATE ON fgc.clawback_candidate
    FOR EACH ROW EXECUTE FUNCTION fgc.set_updated_at();

-- 기존 원천을 한 번 편입한다. 이후 관측은 업무 처리 트랜잭션에서 같은 유일키로 UPSERT한다.
INSERT INTO fgc.clawback_candidate (contract_id, trigger_type, status_event_id, source_version, event_date)
SELECT e.contract_id, 'STATUS_EVENT', e.contract_status_event_id, md5(to_jsonb(e)::text),
       (e.effective_at AT TIME ZONE 'Asia/Seoul')::date
FROM fgc.contract_status_event e WHERE e.new_status IN ('TERMINATED', 'LAPSED', 'CANCELLED');
INSERT INTO fgc.clawback_candidate (contract_id, trigger_type, arbitrage_check_id, source_version, event_date)
SELECT a.contract_id, 'ARBITRAGE', a.arbitrage_check_id, md5(to_jsonb(a)::text), a.as_of_date
FROM fgc.arbitrage_check a WHERE a.payment_stage = 'GA_TO_FC' AND a.result_status = 'CANDIDATE';
