-- 긴급·높음은 미처리, 그 외는 검토대상에서 검토를 시작한다.
ALTER TABLE fgc.exception_case DROP CONSTRAINT exception_case_status_check;
ALTER TABLE fgc.exception_case ADD CONSTRAINT exception_case_status_check
    CHECK (status IN ('NEW', 'REVIEW_REQUIRED', 'IN_REVIEW', 'RESOLVED', 'REJECTED'));
ALTER TABLE fgc.exception_action DROP CONSTRAINT exception_action_to_status_check;
ALTER TABLE fgc.exception_action ADD CONSTRAINT exception_action_to_status_check
    CHECK (to_status IN ('NEW', 'REVIEW_REQUIRED', 'IN_REVIEW', 'RESOLVED', 'REJECTED'));

-- 배치 검출 함수와 직접 INSERT/UPSERT도 같은 초기 상태 규칙을 적용한다.
-- 검토중·종결 상태는 심각도가 바뀌어도 유지한다. 재발로 NEW가 되면 다시 분류한다.
CREATE FUNCTION fgc.set_exception_initial_status()
RETURNS trigger LANGUAGE plpgsql SET search_path = fgc, pg_temp AS $$
BEGIN
    IF NEW.status IN ('NEW', 'REVIEW_REQUIRED') THEN
        NEW.status := CASE WHEN NEW.severity IN ('CRITICAL', 'HIGH')
                           THEN 'NEW' ELSE 'REVIEW_REQUIRED' END;
    END IF;
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_exception_case_initial_status
    BEFORE INSERT OR UPDATE OF status, severity ON fgc.exception_case
    FOR EACH ROW EXECUTE FUNCTION fgc.set_exception_initial_status();

UPDATE fgc.exception_case SET status = 'REVIEW_REQUIRED'
 WHERE status = 'NEW' AND severity NOT IN ('CRITICAL', 'HIGH');

DROP INDEX fgc.uq_exception_active_journal_correction;
CREATE UNIQUE INDEX uq_exception_active_journal_correction
    ON fgc.exception_case (source_entity_type, source_entity_id, exception_type, COALESCE(policy_version_id, -1))
    WHERE source_entity_type = 'JOURNAL_HEADER'
      AND exception_type = 'JOURNAL_CORRECTION_REQUIRED'
      AND status IN ('NEW', 'REVIEW_REQUIRED', 'IN_REVIEW');
