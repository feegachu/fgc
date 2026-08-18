-- SRC-032 D-01~D-06
-- exception_case는 관리자가 처리하는 업무건, exception_occurrence는 실행별 검출 이력이다.
-- 적용된 V1/V15는 수정하지 않고 이 후속 마이그레이션에서 13종 상위 유형으로 수렴한다.

-- 1. 업무건에 검출 요약과 상세 원인을 추가한다.
ALTER TABLE fgc.exception_case
    ADD COLUMN validation_month date,
    ADD COLUMN reason_code varchar(80),
    ADD COLUMN first_detected_run_id bigint REFERENCES fgc.validation_run(validation_run_id),
    ADD COLUMN last_detected_run_id bigint REFERENCES fgc.validation_run(validation_run_id),
    ADD COLUMN first_detected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN last_detected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    ADD COLUMN detection_count integer NOT NULL DEFAULT 1;

ALTER TABLE fgc.exception_case
    ADD CONSTRAINT ck_exception_case_validation_month
        CHECK (validation_month IS NULL OR fgc.is_first_day_of_month(validation_month)),
    ADD CONSTRAINT ck_exception_case_detection_count
        CHECK (detection_count >= 1);

COMMENT ON COLUMN fgc.exception_case.exception_key
    IS 'SRC-032 안정 업무키. 검증월·업무대상·업무식별자·세부차원으로 구성하며 실행 ID와 실행별 결과 PK를 제외한다';
COMMENT ON COLUMN fgc.exception_case.reason_code
    IS '상위 exception_type 아래의 구체 원인 코드. 관리자 검색·원인 설명·조치 안내에 사용';

-- V15가 임시 허용한 기술 원인은 상위 업무유형이 아니라 CAP 검토필요의 상세 원인으로 보존한다.
UPDATE fgc.exception_case
   SET exception_type = 'CAP_REVIEW_REQUIRED',
       reason_code = 'CAP_RULE_MISMATCH'
 WHERE exception_type = 'CAP_RULE_MISMATCH';

ALTER TABLE fgc.exception_case
    DROP CONSTRAINT IF EXISTS exception_case_exception_type_check;

ALTER TABLE fgc.exception_case
    ADD CONSTRAINT exception_case_exception_type_check
    CHECK (exception_type IN (
        'CAP_WARNING',
        'CAP_VIOLATION',
        'CAP_REVIEW_REQUIRED',
        'RECONCILIATION_MISMATCH',
        'JOURNAL_IMBALANCE',
        'ARBITRAGE_CANDIDATE',
        'REFUND_TABLE_MISSING',
        'PRODUCT_CODE_MISMATCH',
        'POLICY_MISSING',
        'POLICY_DUPLICATE',
        'ALLOCATION_EVIDENCE_MISSING',
        'DATA_QUALITY',
        'OTHER'
    ));

-- 2. 실행별 불변 검출 이력.
CREATE TABLE fgc.exception_occurrence (
    exception_occurrence_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    exception_case_id       bigint       NOT NULL REFERENCES fgc.exception_case(exception_case_id),
    validation_run_id       bigint       NOT NULL REFERENCES fgc.validation_run(validation_run_id),
    exception_type          varchar(50)  NOT NULL CHECK (exception_type IN (
                                   'CAP_WARNING','CAP_VIOLATION','CAP_REVIEW_REQUIRED',
                                   'RECONCILIATION_MISMATCH','JOURNAL_IMBALANCE','ARBITRAGE_CANDIDATE',
                                   'REFUND_TABLE_MISSING','PRODUCT_CODE_MISMATCH','POLICY_MISSING','POLICY_DUPLICATE',
                                   'ALLOCATION_EVIDENCE_MISSING','DATA_QUALITY','OTHER'
                                 )),
    reason_code             varchar(80),
    source_entity_type      varchar(60)  NOT NULL,
    source_entity_id        varchar(100) NOT NULL,
    evidence_snapshot       jsonb        NOT NULL DEFAULT '{}'::jsonb,
    is_new_case             boolean      NOT NULL DEFAULT false,
    was_reopened            boolean      NOT NULL DEFAULT false,
    detected_at             timestamptz  NOT NULL DEFAULT clock_timestamp()
);

COMMENT ON TABLE fgc.exception_occurrence
    IS 'SRC-032 월 통합검증 실행별 예외 검출 증거. 업무건과 분리된 append-only 이력';

CREATE TRIGGER trg_exception_occurrence_append_only
BEFORE UPDATE OR DELETE ON fgc.exception_occurrence
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

-- 3. 기존 실행별 예외를 occurrence로 먼저 보존한다.
UPDATE fgc.exception_case ec
   SET validation_month = vr.validation_month,
       first_detected_run_id = ec.validation_run_id,
       last_detected_run_id = ec.validation_run_id,
       first_detected_at = ec.created_at,
       last_detected_at = ec.created_at
  FROM fgc.validation_run vr
 WHERE vr.validation_run_id = ec.validation_run_id;

UPDATE fgc.exception_case ec
   SET reason_code = CASE
       WHEN ec.reason_code IS NOT NULL THEN ec.reason_code
       WHEN ec.exception_type = 'CAP_VIOLATION' THEN 'CAP_LIMIT_VIOLATION'
       WHEN ec.exception_type = 'CAP_REVIEW_REQUIRED' THEN 'CAP_CALCULATION_REVIEW_REQUIRED'
       WHEN ec.exception_type = 'JOURNAL_IMBALANCE' THEN 'JOURNAL_IMBALANCE'
       WHEN ec.exception_type = 'POLICY_MISSING' THEN 'POLICY_MISSING'
       WHEN ec.exception_type = 'POLICY_DUPLICATE' THEN 'POLICY_DUPLICATE'
       WHEN ec.exception_type = 'REFUND_TABLE_MISSING' THEN 'REFUND_TABLE_MISSING'
       WHEN ec.exception_type = 'PRODUCT_CODE_MISMATCH' THEN 'PRODUCT_CODE_MISMATCH'
       ELSE ec.exception_type
   END;

UPDATE fgc.exception_case ec
   SET reason_code = COALESCE(rr.primary_reason_code, rr.result_type, 'RECONCILIATION_MISMATCH')
  FROM fgc.reconciliation_result rr
 WHERE ec.source_entity_type = 'RECONCILIATION_RESULT'
   AND ec.source_entity_id = CAST(rr.reconciliation_result_id AS varchar);

UPDATE fgc.exception_case ec
   SET reason_code = CASE
       WHEN ac.result_status = 'CANDIDATE' THEN 'ARBITRAGE_LIMIT_EXCEEDED'
       WHEN COALESCE(ac.calculation_snapshot ->> 'decisionReason', '') LIKE '%금융 스냅샷이 없습니다%'
           THEN 'FINANCIAL_SNAPSHOT_MISSING'
       WHEN COALESCE(ac.calculation_snapshot ->> 'decisionReason', '') LIKE '%환급률표%없습니다%'
          OR COALESCE(ac.calculation_snapshot ->> 'decisionReason', '') LIKE '%환급률표 ID가 없습니다%'
           THEN 'REFUND_TABLE_MISSING'
       WHEN COALESCE(ac.calculation_snapshot ->> 'decisionReason', '') LIKE '%상품코드%'
          OR COALESCE(ac.calculation_snapshot ->> 'decisionReason', '') LIKE '%환급률표가 일치하지 않습니다%'
           THEN 'PRODUCT_CODE_MISMATCH'
       WHEN COALESCE(ac.calculation_snapshot ->> 'decisionReason', '') LIKE '%여러 건%'
           THEN 'POLICY_DUPLICATE'
       ELSE 'ARBITRAGE_DATA_REVIEW_REQUIRED'
   END
  FROM fgc.arbitrage_check ac
 WHERE ec.source_entity_type = 'ARBITRAGE_CHECK'
   AND ec.source_entity_id = CAST(ac.arbitrage_check_id AS varchar);

INSERT INTO fgc.exception_occurrence (
    exception_case_id, validation_run_id, exception_type, reason_code,
    source_entity_type, source_entity_id, evidence_snapshot,
    is_new_case, was_reopened, detected_at
)
SELECT ec.exception_case_id,
       ec.validation_run_id,
       ec.exception_type,
       ec.reason_code,
       ec.source_entity_type,
       ec.source_entity_id,
       jsonb_build_object(
           'migratedFromExceptionCaseId', ec.exception_case_id,
           'title', ec.title,
           'description', ec.description
       ),
       true,
       false,
       ec.created_at
  FROM fgc.exception_case ec
 WHERE ec.validation_run_id IS NOT NULL;

-- 4. 기존 실행별 키를 동일 검증월의 안정 업무키로 재계산한다.
CREATE TEMP TABLE tmp_exception_rekey ON COMMIT DROP AS
WITH keyed AS (
    SELECT ec.exception_case_id AS old_case_id,
           CASE
               WHEN ec.validation_run_id IS NULL THEN ec.exception_key
               WHEN ec.source_entity_type = 'CAP_CHECK' AND cc.cap_check_id IS NOT NULL THEN
                   CONCAT(ec.exception_type, ':', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                          ':CONTRACT:', ec.contract_id, ':', cc.payment_stage)
               WHEN ec.source_entity_type = 'ARBITRAGE_CHECK' AND ac.arbitrage_check_id IS NOT NULL THEN
                   CONCAT(ec.exception_type, ':', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                          ':CONTRACT:', ec.contract_id, ':', ac.payment_stage, ':', ec.reason_code)
               WHEN ec.source_entity_type = 'RECONCILIATION_RESULT'
                    AND rr.reconciliation_result_id IS NOT NULL THEN
                   CONCAT(ec.exception_type, ':', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                          ':CONTRACT:', ec.contract_id, ':', rn.payment_stage, ':',
                          LEFT(rr.match_group_key, 140), ':', MD5(rr.match_group_key))
               WHEN ec.source_entity_type = 'JOURNAL_HEADER' AND jh.journal_header_id IS NOT NULL THEN
                   CONCAT(ec.exception_type, ':', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                          ':CONTRACT:', COALESCE(CAST(ec.contract_id AS varchar), '-'), ':',
                          jh.journal_type, ':', jh.source_entity_type)
               ELSE
                   CONCAT(ec.exception_type, ':', TO_CHAR(vr.validation_month, 'YYYY-MM'),
                          ':', ec.source_entity_type, ':',
                          COALESCE(CAST(ec.contract_id AS varchar), ec.source_entity_id), ':',
                          COALESCE(ec.reason_code, ec.exception_type))
           END AS stable_key
      FROM fgc.exception_case ec
      LEFT JOIN fgc.validation_run vr ON vr.validation_run_id = ec.validation_run_id
      LEFT JOIN fgc.cap_check cc
             ON ec.source_entity_type = 'CAP_CHECK'
            AND ec.source_entity_id = CAST(cc.cap_check_id AS varchar)
      LEFT JOIN fgc.arbitrage_check ac
             ON ec.source_entity_type = 'ARBITRAGE_CHECK'
            AND ec.source_entity_id = CAST(ac.arbitrage_check_id AS varchar)
      LEFT JOIN fgc.reconciliation_result rr
             ON ec.source_entity_type = 'RECONCILIATION_RESULT'
            AND ec.source_entity_id = CAST(rr.reconciliation_result_id AS varchar)
      LEFT JOIN fgc.reconciliation_run rn ON rn.reconciliation_run_id = rr.reconciliation_run_id
      LEFT JOIN fgc.journal_header jh
             ON ec.source_entity_type = 'JOURNAL_HEADER'
            AND ec.source_entity_id = CAST(jh.journal_header_id AS varchar)
)
SELECT old_case_id,
       stable_key,
       MIN(old_case_id) OVER (PARTITION BY stable_key) AS canonical_case_id
  FROM keyed;

-- append-only 트리거를 이관 동안만 끄고, 모든 조치 이력을 대표 업무건으로 옮긴다.
ALTER TABLE fgc.exception_action DISABLE TRIGGER trg_exception_action_append_only;

CREATE TEMP TABLE tmp_exception_action_move ON COMMIT DROP AS
SELECT ea.exception_action_id,
       r.canonical_case_id,
       COALESCE(base.max_seq, 0)
         + ROW_NUMBER() OVER (
               PARTITION BY r.canonical_case_id
               ORDER BY ea.action_at, ea.exception_action_id
           ) AS new_action_seq
  FROM fgc.exception_action ea
  JOIN tmp_exception_rekey r ON r.old_case_id = ea.exception_case_id
  LEFT JOIN LATERAL (
      SELECT MAX(existing.action_seq) AS max_seq
        FROM fgc.exception_action existing
       WHERE existing.exception_case_id = r.canonical_case_id
  ) base ON true
 WHERE r.old_case_id <> r.canonical_case_id;

UPDATE fgc.exception_action ea
   SET action_seq = move.new_action_seq
  FROM tmp_exception_action_move move
 WHERE move.exception_action_id = ea.exception_action_id;

UPDATE fgc.exception_action ea
   SET exception_case_id = move.canonical_case_id
  FROM tmp_exception_action_move move
 WHERE move.exception_action_id = ea.exception_action_id;

ALTER TABLE fgc.exception_action ENABLE TRIGGER trg_exception_action_append_only;

-- occurrence 역시 대표 업무건으로 옮긴다. 같은 실행 내 중복은 가장 이른 검출 하나만 남긴다.
ALTER TABLE fgc.exception_occurrence DISABLE TRIGGER trg_exception_occurrence_append_only;

UPDATE fgc.exception_occurrence occurrence
   SET exception_case_id = r.canonical_case_id
  FROM tmp_exception_rekey r
 WHERE r.old_case_id = occurrence.exception_case_id;

DELETE FROM fgc.exception_occurrence occurrence
 USING (
     SELECT exception_occurrence_id,
            ROW_NUMBER() OVER (
                PARTITION BY exception_case_id, validation_run_id
                ORDER BY detected_at, exception_occurrence_id
            ) AS duplicate_no
       FROM fgc.exception_occurrence
 ) ranked
 WHERE ranked.exception_occurrence_id = occurrence.exception_occurrence_id
   AND ranked.duplicate_no > 1;

-- 대표 업무건은 가장 최근 실행의 원천·설명을 보여 주되 담당자 처리 상태는 열린 상태를 우선 보존한다.
WITH latest AS (
    SELECT DISTINCT ON (r.canonical_case_id)
           r.canonical_case_id,
           ec.validation_run_id,
           ec.validation_month,
           ec.reason_code,
           ec.severity,
           ec.contract_id,
           ec.agent_id,
           ec.policy_version_id,
           ec.source_entity_type,
           ec.source_entity_id,
           ec.title,
           ec.description
      FROM tmp_exception_rekey r
      JOIN fgc.exception_case ec ON ec.exception_case_id = r.old_case_id
      LEFT JOIN fgc.validation_run vr ON vr.validation_run_id = ec.validation_run_id
     ORDER BY r.canonical_case_id, vr.run_no DESC NULLS LAST, ec.updated_at DESC, ec.exception_case_id DESC
), rollup AS (
    SELECT r.canonical_case_id,
           CASE
               WHEN BOOL_OR(ec.status = 'IN_REVIEW') THEN 'IN_REVIEW'
               WHEN BOOL_OR(ec.status = 'NEW') THEN 'NEW'
               WHEN BOOL_OR(ec.status = 'RESOLVED') THEN 'RESOLVED'
               ELSE 'REJECTED'
           END AS merged_status,
           (ARRAY_AGG(ec.assigned_to ORDER BY ec.updated_at DESC)
               FILTER (WHERE ec.assigned_to IS NOT NULL))[1] AS merged_assigned_to,
           MIN(ec.created_at) AS first_created_at,
           MAX(ec.resolved_at) AS last_resolved_at
      FROM tmp_exception_rekey r
      JOIN fgc.exception_case ec ON ec.exception_case_id = r.old_case_id
     GROUP BY r.canonical_case_id
)
UPDATE fgc.exception_case canonical
   SET validation_run_id = latest.validation_run_id,
       validation_month = latest.validation_month,
       reason_code = latest.reason_code,
       severity = latest.severity,
       contract_id = latest.contract_id,
       agent_id = latest.agent_id,
       policy_version_id = latest.policy_version_id,
       source_entity_type = latest.source_entity_type,
       source_entity_id = latest.source_entity_id,
       title = latest.title,
       description = latest.description,
       status = rollup.merged_status,
       assigned_to = COALESCE(rollup.merged_assigned_to, canonical.assigned_to),
       resolved_at = CASE
           WHEN rollup.merged_status IN ('RESOLVED', 'REJECTED') THEN rollup.last_resolved_at
           ELSE NULL
       END,
       created_at = rollup.first_created_at
  FROM latest
  JOIN rollup ON rollup.canonical_case_id = latest.canonical_case_id
 WHERE canonical.exception_case_id = latest.canonical_case_id;

DELETE FROM fgc.exception_case duplicate
 USING tmp_exception_rekey r
 WHERE duplicate.exception_case_id = r.old_case_id
   AND r.old_case_id <> r.canonical_case_id;

UPDATE fgc.exception_case canonical
   SET exception_key = r.stable_key
  FROM tmp_exception_rekey r
 WHERE canonical.exception_case_id = r.canonical_case_id;

-- 최초 occurrence만 신규 업무건이며 나머지는 재검출이다.
UPDATE fgc.exception_occurrence SET is_new_case = false;

WITH first_occurrence AS (
    SELECT DISTINCT ON (occurrence.exception_case_id)
           occurrence.exception_occurrence_id
      FROM fgc.exception_occurrence occurrence
      JOIN fgc.validation_run vr ON vr.validation_run_id = occurrence.validation_run_id
     ORDER BY occurrence.exception_case_id, vr.run_no, occurrence.detected_at,
              occurrence.exception_occurrence_id
)
UPDATE fgc.exception_occurrence occurrence
   SET is_new_case = true
  FROM first_occurrence first
 WHERE first.exception_occurrence_id = occurrence.exception_occurrence_id;

WITH detection AS (
    SELECT occurrence.exception_case_id,
           COUNT(*) AS detection_count,
           (ARRAY_AGG(occurrence.validation_run_id
               ORDER BY occurrence.detected_at, occurrence.exception_occurrence_id))[1]
               AS first_run_id,
           (ARRAY_AGG(occurrence.validation_run_id
               ORDER BY occurrence.detected_at DESC, occurrence.exception_occurrence_id DESC))[1]
               AS last_run_id,
           MIN(occurrence.detected_at) AS first_detected_at,
           MAX(occurrence.detected_at) AS last_detected_at
      FROM fgc.exception_occurrence occurrence
     GROUP BY occurrence.exception_case_id
)
UPDATE fgc.exception_case ec
   SET detection_count = detection.detection_count,
       first_detected_run_id = detection.first_run_id,
       last_detected_run_id = detection.last_run_id,
       first_detected_at = detection.first_detected_at,
       last_detected_at = detection.last_detected_at,
       validation_run_id = detection.last_run_id
  FROM detection
 WHERE detection.exception_case_id = ec.exception_case_id;

ALTER TABLE fgc.exception_occurrence ENABLE TRIGGER trg_exception_occurrence_append_only;

ALTER TABLE fgc.exception_occurrence
    ADD CONSTRAINT uq_exception_occurrence_case_run
        UNIQUE (exception_case_id, validation_run_id);

CREATE INDEX ix_exception_occurrence_run
    ON fgc.exception_occurrence(validation_run_id, is_new_case, exception_case_id);
CREATE INDEX ix_exception_case_month_status
    ON fgc.exception_case(validation_month, status, last_detected_at DESC);
CREATE INDEX ix_exception_case_reason
    ON fgc.exception_case(reason_code, status);

-- 5. 모든 월 검증 생성 경로가 공유하는 원자적 기록 함수.
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
        clock_timestamp(), clock_timestamp(), 0,
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
    END IF;

    INSERT INTO exception_occurrence (
        exception_case_id, validation_run_id, exception_type, reason_code,
        source_entity_type, source_entity_id, evidence_snapshot,
        is_new_case, was_reopened
    ) VALUES (
        v_case_id, p_validation_run_id, p_exception_type, p_reason_code,
        p_source_entity_type, p_source_entity_id,
        COALESCE(p_evidence_snapshot, '{}'::jsonb),
        v_new_case, v_previous_status = 'RESOLVED'
    )
    ON CONFLICT ON CONSTRAINT uq_exception_occurrence_case_run DO NOTHING
    RETURNING exception_occurrence_id INTO v_occurrence_id;

    IF v_occurrence_id IS NOT NULL THEN
        v_reopened := v_previous_status = 'RESOLVED';

        UPDATE exception_case
           SET exception_type = p_exception_type,
               reason_code = p_reason_code,
               severity = p_severity,
               status = CASE WHEN status = 'RESOLVED' THEN 'NEW' ELSE status END,
               resolved_at = CASE WHEN status = 'RESOLVED' THEN NULL ELSE resolved_at END,
               validation_run_id = p_validation_run_id,
               validation_month = v_validation_month,
               first_detected_run_id = COALESCE(first_detected_run_id, p_validation_run_id),
               last_detected_run_id = p_validation_run_id,
               first_detected_at = CASE
                    WHEN detection_count = 0 THEN clock_timestamp()
                    ELSE first_detected_at
                END,
                last_detected_at = clock_timestamp(),
                detection_count = detection_count + 1,
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
) IS 'SRC-032 안정 업무건 UPSERT + 실행별 occurrence 멱등 기록. 같은 실행 재시도와 새 실행 재검출을 구분';
