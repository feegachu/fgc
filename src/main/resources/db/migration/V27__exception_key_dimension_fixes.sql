-- SRC-032 후속 보정 (PR #197 리뷰 반영)
-- 1) 검증원장 업무키에 분개 원천 식별자를 추가한다 — 같은 실행에서 같은 계약·분개유형의
--    서로 다른 불균형 분개가 한 업무건으로 합쳐져 두 번째 건의 증거가 소실되던 문제.
-- 2) V23_1 이관 ELSE 분기가 남긴 레거시 DATA_QUALITY 키의 'INSURANCE_CONTRACT' 세그먼트를
--    런타임 규칙('CONTRACT')으로 보정한다 — 재검출 시 기존 업무건에 붙지 못하고
--    중복 생성되던 문제 (FGC-FUN-052 인수조건).
-- 3) record_exception_detection: 검출 이력이 없는 REJECTED 업무건 재검출 시
--    v_evidence_changed 가 NULL 로 남아 was_reopened NOT NULL 에 걸리는 경로를 막는다.

-- 1. 검증원장 업무키 전진 보정. 이미 원천 식별자가 붙었거나 보정 키가 선점된 행은 건너뛴다.
UPDATE fgc.exception_case ec
   SET exception_key = CONCAT(ec.exception_key, ':', jh.source_entity_id)
  FROM fgc.journal_header jh
 WHERE ec.exception_type = 'JOURNAL_IMBALANCE'
   AND ec.source_entity_type = 'JOURNAL_HEADER'
   AND jh.journal_header_id = CAST(ec.source_entity_id AS bigint)
   AND ec.exception_key NOT LIKE CONCAT('%:', jh.source_entity_id)
   AND NOT EXISTS (
       SELECT 1
         FROM fgc.exception_case other
        WHERE other.exception_key = CONCAT(ec.exception_key, ':', jh.source_entity_id)
   );

-- 2. 레거시 DATA_QUALITY(원천 INSURANCE_CONTRACT) 키 세그먼트 보정.
--    구 CAP_CALCULATION_FAILED 건은 키에 지급단계 차원이 없던 시절 값이라 완전 재구성이
--    불가능하다 — 그 건은 다음 검출 때 새 키의 업무건으로 1회 갈라진 뒤 수렴한다.
UPDATE fgc.exception_case ec
   SET exception_key = REPLACE(ec.exception_key, ':INSURANCE_CONTRACT:', ':CONTRACT:')
 WHERE ec.source_entity_type = 'INSURANCE_CONTRACT'
   AND ec.exception_key LIKE '%:INSURANCE_CONTRACT:%'
   AND NOT EXISTS (
       SELECT 1
         FROM fgc.exception_case other
        WHERE other.exception_key = REPLACE(ec.exception_key, ':INSURANCE_CONTRACT:', ':CONTRACT:')
   );

-- 3. V24 함수 재정의 — 변경점은 v_reopened 계산의 COALESCE 가드 한 줄이다.
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

    -- 검출 이력이 없는 업무건이면 SELECT INTO 가 v_evidence_changed 를 NULL 로 만든다 —
    -- 증거 변화를 증명할 수 없으므로 REJECTED 는 유지(false)로 본다.
    v_reopened := v_previous_status = 'RESOLVED'
        OR (v_previous_status = 'REJECTED' AND COALESCE(v_evidence_changed, false));

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
) IS 'SRC-032 안정 업무건 UPSERT + 실행별 occurrence 멱등 기록. V27: 이력 없는 REJECTED 재검출 NULL 가드';
