-- SRC-032: V23 적용 이후 확정된 차익거래 업무키와 최초 검출 카운터를 전진 보정한다.

UPDATE fgc.exception_case ec
   SET exception_key = CONCAT(
           'ARBITRAGE_CANDIDATE:', TO_CHAR(ec.validation_month, 'YYYY-MM'),
           ':CONTRACT:', ec.contract_id, ':', ac.payment_stage
       )
  FROM fgc.arbitrage_check ac
 WHERE ec.exception_type = 'ARBITRAGE_CANDIDATE'
   AND ec.source_entity_type = 'ARBITRAGE_CHECK'
   AND ac.arbitrage_check_id = ec.source_entity_id::bigint;

CREATE OR REPLACE FUNCTION fgc.record_exception_detection(
    p_exception_key varchar,
    p_exception_type varchar,
    p_reason_code varchar,
    p_severity varchar,
    p_validation_run_id bigint,
    p_contract_id bigint,
    p_agent_id bigint,
    p_policy_version_id bigint,
    p_source_entity_type varchar,
    p_source_entity_id varchar,
    p_title varchar,
    p_description varchar,
    p_evidence_snapshot jsonb
)
RETURNS TABLE (
    recorded_case_id bigint,
    new_case boolean,
    new_occurrence boolean,
    reopened boolean
)
LANGUAGE plpgsql
VOLATILE
SET search_path = fgc, pg_temp
AS $$
DECLARE
    v_case_id bigint;
    v_occurrence_id bigint;
    v_validation_month date;
    v_previous_status varchar(20);
    v_new_case boolean := false;
    v_reopened boolean := false;
    v_evidence_changed boolean := false;
BEGIN
    SELECT validation_month
      INTO v_validation_month
      FROM validation_run
     WHERE validation_run_id = p_validation_run_id;

    IF v_validation_month IS NULL THEN
        RAISE EXCEPTION 'validation_run % does not exist', p_validation_run_id;
    END IF;

    INSERT INTO exception_case (
        exception_key, exception_type, reason_code, severity, status,
        validation_run_id, validation_month,
        first_detected_run_id, last_detected_run_id,
        first_detected_at, last_detected_at, detection_count,
        contract_id, agent_id, policy_version_id,
        source_entity_type, source_entity_id, title, description
    ) VALUES (
        p_exception_key, p_exception_type, p_reason_code, p_severity, 'NEW',
        p_validation_run_id, v_validation_month,
        p_validation_run_id, p_validation_run_id,
        clock_timestamp(), clock_timestamp(), 1,
        p_contract_id, p_agent_id, p_policy_version_id,
        p_source_entity_type, p_source_entity_id, p_title, p_description
    )
    ON CONFLICT ON CONSTRAINT uq_exception_key DO NOTHING
    RETURNING exception_case_id, status
         INTO v_case_id, v_previous_status;

    v_new_case := FOUND;

    IF NOT v_new_case THEN
        SELECT exception_case_id, status
          INTO v_case_id, v_previous_status
          FROM exception_case
         WHERE exception_key = p_exception_key
         FOR UPDATE;

        SELECT COALESCE(
                   (occurrence.evidence_snapshot
                       - ARRAY['capCheckId', 'arbitrageCheckId', 'reconciliationResultId', 'journalHeaderId'])
                   IS DISTINCT FROM
                   (COALESCE(p_evidence_snapshot, '{}'::jsonb)
                       - ARRAY['capCheckId', 'arbitrageCheckId', 'reconciliationResultId', 'journalHeaderId']),
                   true
               )
          INTO v_evidence_changed
          FROM exception_occurrence occurrence
         WHERE occurrence.exception_case_id = v_case_id
         ORDER BY occurrence.detected_at DESC, occurrence.exception_occurrence_id DESC
         LIMIT 1;
    END IF;

    v_reopened := v_previous_status = 'RESOLVED'
        OR (v_previous_status = 'REJECTED' AND v_evidence_changed);

    INSERT INTO exception_occurrence (
        exception_case_id, validation_run_id, exception_type, reason_code,
        source_entity_type, source_entity_id, evidence_snapshot,
        is_new_case, was_reopened
    ) VALUES (
        v_case_id, p_validation_run_id, p_exception_type, p_reason_code,
        p_source_entity_type, p_source_entity_id,
        COALESCE(p_evidence_snapshot, '{}'::jsonb),
        v_new_case, v_reopened
    )
    ON CONFLICT ON CONSTRAINT uq_exception_occurrence_case_run DO NOTHING
    RETURNING exception_occurrence_id INTO v_occurrence_id;

    IF v_occurrence_id IS NOT NULL THEN
        UPDATE exception_case
           SET exception_type = p_exception_type,
               reason_code = p_reason_code,
               severity = p_severity,
               status = CASE WHEN v_reopened THEN 'NEW' ELSE status END,
               resolved_at = CASE WHEN v_reopened THEN NULL ELSE resolved_at END,
               validation_run_id = p_validation_run_id,
               validation_month = v_validation_month,
               first_detected_run_id = COALESCE(first_detected_run_id, p_validation_run_id),
               last_detected_run_id = p_validation_run_id,
               first_detected_at = COALESCE(first_detected_at, clock_timestamp()),
               last_detected_at = clock_timestamp(),
               detection_count = CASE
                   WHEN v_new_case THEN detection_count
                   ELSE detection_count + 1
               END,
               contract_id = COALESCE(p_contract_id, contract_id),
               agent_id = COALESCE(p_agent_id, agent_id),
               policy_version_id = COALESCE(p_policy_version_id, policy_version_id),
               source_entity_type = p_source_entity_type,
               source_entity_id = p_source_entity_id,
               title = p_title,
               description = p_description
         WHERE exception_case_id = v_case_id;
    END IF;

    RETURN QUERY
    SELECT v_case_id, v_new_case, v_occurrence_id IS NOT NULL, v_reopened;
END;
$$;

COMMENT ON FUNCTION fgc.record_exception_detection(
    varchar, varchar, varchar, varchar, bigint, bigint, bigint, bigint,
    varchar, varchar, varchar, varchar, jsonb
) IS 'SRC-032 안정 업무건 UPSERT + 실행별 occurrence 멱등 기록';
