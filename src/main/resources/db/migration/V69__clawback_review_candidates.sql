-- FUN-054 (#668): 정책 없는 원천도 저장한다. case/계산/통지 이력은 변경하지 않는다.
CREATE TABLE fgc.clawback_candidate (
    candidate_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_id bigint NOT NULL REFERENCES fgc.insurance_contract(contract_id),
    trigger_type varchar(20) NOT NULL,
    status_event_id bigint REFERENCES fgc.contract_status_event(contract_status_event_id),
    arbitrage_check_id bigint REFERENCES fgc.arbitrage_check(arbitrage_check_id),
    source_id bigint GENERATED ALWAYS AS (COALESCE(status_event_id, arbitrage_check_id)) STORED,
    source_version varchar(128) NOT NULL,
    event_date date NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_clawback_candidate_source CHECK (
        num_nonnulls(status_event_id, arbitrage_check_id) = 1
        AND trigger_type = CASE WHEN status_event_id IS NOT NULL THEN 'STATUS_EVENT' ELSE 'ARBITRAGE' END
    ),
    CONSTRAINT uq_clawback_candidate_event UNIQUE (status_event_id)
);
-- 유형별로 실제 충돌을 판정하는 인덱스를 하나만 두고 동일 인덱스를 UPSERT arbiter로 사용한다.
CREATE UNIQUE INDEX uq_clawback_candidate_arbitrage ON fgc.clawback_candidate (contract_id, event_date)
    WHERE status_event_id IS NULL;
CREATE INDEX ix_clawback_candidate_event_date ON fgc.clawback_candidate (event_date DESC, candidate_id DESC);
CREATE INDEX ix_clawback_candidate_contract ON fgc.clawback_candidate (contract_id, candidate_id);
COMMENT ON TABLE fgc.clawback_candidate IS 'FUN-054 환수 검토 원천. 정책/계산이 없어도 보존하며 조회로 생성하지 않는다';
COMMENT ON COLUMN fgc.clawback_candidate.source_version IS '관측한 원천행의 fingerprint. API sourceVersion은 현재 지급/스케줄 근거까지 포함';

CREATE FUNCTION fgc.guard_clawback_candidate_source() RETURNS trigger LANGUAGE plpgsql SET search_path = fgc, pg_temp AS $$
BEGIN
    IF TG_OP = 'UPDATE' AND ROW(NEW.contract_id, NEW.trigger_type, NEW.status_event_id, NEW.event_date)
        IS DISTINCT FROM ROW(OLD.contract_id, OLD.trigger_type, OLD.status_event_id, OLD.event_date) THEN
        RAISE EXCEPTION 'Clawback candidate identity is immutable';
    END IF;
    IF NEW.status_event_id IS NOT NULL THEN
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
FROM (
    SELECT DISTINCT ON (contract_id, as_of_date) * FROM fgc.arbitrage_check
    WHERE payment_stage = 'GA_TO_FC' ORDER BY contract_id, as_of_date, arbitrage_check_id DESC
) a
WHERE EXISTS (SELECT 1 FROM fgc.arbitrage_check detected
    WHERE detected.contract_id = a.contract_id AND detected.as_of_date = a.as_of_date
      AND detected.payment_stage = a.payment_stage AND detected.result_status = 'CANDIDATE');
