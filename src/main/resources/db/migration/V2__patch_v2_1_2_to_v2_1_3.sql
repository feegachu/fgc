-- ============================================================================
-- FGC PostgreSQL Schema Patch v2.1.2 -> v2.1.3
-- 대상 DBMS: PostgreSQL 17
-- 작성 기준일: 2026-08-03
--
-- 목적 (1건)
--   일일 변경분 배치가 "어디까지 처리했는지"를 기록할 자리를 만든다.
--
-- 왜 필요한가
--   1차부터 Spring Batch로 DailyChangedContractJob(일일 변경분 보정 배치)을 돌립니다.
--   이 배치는 모든 계약을 매일 전부 다시 검사하지 않고, 아래처럼 "바뀐 것만" 읽습니다.
--
--     WHERE c.updated_at > :lastProcessedAt
--
--   그런데 :lastProcessedAt 을 어딘가에 적어 두어야 다음 날 이어서 읽을 수 있습니다.
--   v2.1.2 스키마에는 그 자리가 없었습니다.
--
--   Spring Batch 메타테이블(BATCH_JOB_EXECUTION_PARAMS)에서 직전 성공 시각을 캐낼 수도
--   있지만, 조회 쿼리가 복잡해지고 배치 메타테이블을 업무 로직이 읽는 구조가 됩니다.
--   업무용 워터마크는 업무 스키마에 두는 편이 읽기 쉽습니다.
--
-- 워터마크(watermark)란
--   "여기까지는 처리했다"고 표시해 두는 기준선입니다.
--   강물에 남은 물자국처럼, 다음에는 그 위(그 이후)만 보면 됩니다.
--
-- ★ 운영 규칙 (반드시 지킬 것)
--   워터마크는 Step 이 성공한 뒤에만 전진시킵니다.
--   실패했는데 미리 전진시키면 그 구간을 영영 다시 안 읽게 됩니다.
--   반대로 실패해서 전진하지 못하면 다음 날 같은 구간을 다시 읽지만,
--   기존 UNIQUE 제약(cap_check · arbitrage_check · exception_key 등)이
--   중복 생성을 막아 주므로 안전합니다. 이것이 멱등성(같은 작업을 두 번 해도
--   결과가 하나만 남는 성질)입니다.
--
-- 데이터 영향
--   기존 행을 읽거나 바꾸지 않습니다. 테이블 1개와 시드 2행만 추가합니다.
--   따라서 데이터가 있는 DB에도 안전하게 적용할 수 있습니다.
--
-- 관련 산출물
--   인터페이스정의서 v2.0 §6(검증 실행 시점 아키텍처) · §7(Spring Batch 인터페이스)
--   요구사항명세서 v2.2.2 FGC-ECR-001(1차 기술환경에 Spring Batch 추가)
-- ============================================================================

BEGIN;
SET search_path TO fgc, public;

-- ----------------------------------------------------------------------------
-- 1. batch_watermark
-- ----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS fgc.batch_watermark (
    job_name            varchar(100) PRIMARY KEY,
    step_name           varchar(100),
    last_processed_at   timestamptz  NOT NULL,
    last_run_id         bigint,
    last_success_at     timestamptz,
    processed_count     bigint       NOT NULL DEFAULT 0 CHECK (processed_count >= 0),
    note                varchar(500),
    updated_by          varchar(60)  NOT NULL DEFAULT 'BATCH',
    updated_at          timestamptz  NOT NULL DEFAULT now()
);

COMMENT ON TABLE  fgc.batch_watermark IS
  '배치 Job이 어디까지 처리했는지 기록하는 기준선. Step 성공 후에만 전진시킨다.';
COMMENT ON COLUMN fgc.batch_watermark.job_name IS
  'Spring Batch Job 이름. 예) DailyChangedContractJob';
COMMENT ON COLUMN fgc.batch_watermark.step_name IS
  'Step 단위로 나눠 기록할 때 사용. 단일 워터마크면 NULL.';
COMMENT ON COLUMN fgc.batch_watermark.last_processed_at IS
  '다음 실행에서 이 시각보다 나중에 바뀐 행만 읽는다. updated_at > last_processed_at';
COMMENT ON COLUMN fgc.batch_watermark.last_run_id IS
  '그때 만들어진 validation_run.validation_run_id. 결과 추적용.';
COMMENT ON COLUMN fgc.batch_watermark.last_success_at IS
  '마지막으로 Step이 성공한 시각. 실패한 실행은 이 값을 바꾸지 않는다.';

-- 워터마크는 되돌리지 않는다(뒤로 가면 이미 처리한 구간을 다시 읽게 됨).
-- 다만 장애 복구를 위해 관리자가 의도적으로 되돌릴 수는 있어야 하므로
-- 트리거로 막지 않고, 되돌린 사실을 감사로그에 남기는 것으로 통제한다.
CREATE OR REPLACE FUNCTION fgc.touch_batch_watermark()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  NEW.updated_at := now();
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_batch_watermark_touch ON fgc.batch_watermark;
CREATE TRIGGER trg_batch_watermark_touch
BEFORE UPDATE ON fgc.batch_watermark
FOR EACH ROW EXECUTE FUNCTION fgc.touch_batch_watermark();

-- ----------------------------------------------------------------------------
-- 2. 1차 Job 2개의 초기 워터마크
--    처음 돌 때 전체를 다 읽지 않도록 시드 데이터 기준일로 잡아 둔다.
-- ----------------------------------------------------------------------------
INSERT INTO fgc.batch_watermark (job_name, step_name, last_processed_at, note, updated_by)
VALUES
  ('DailyChangedContractJob', 'changedContractStep',
   timestamptz '2026-07-01 00:00:00+09',
   '일일 변경분 보정 배치. updated_at > last_processed_at 인 계약만 재검증한다.',
   'MIGRATION'),
  ('MonthlyValidationJob', NULL,
   timestamptz '2026-07-01 00:00:00+09',
   '월 전체 검증 배치. 대상월 파라미터로 돌기 때문에 워터마크는 참고용이다.',
   'MIGRATION')
ON CONFLICT (job_name) DO NOTHING;

COMMIT;

-- ============================================================================
-- 적용 후 확인
-- ============================================================================
-- 1) 테이블과 시드 확인
--    SELECT job_name, last_processed_at, note FROM fgc.batch_watermark ORDER BY job_name;
--
-- 2) 일일 배치가 읽을 대상 확인 (변경분만 나와야 한다)
--    SELECT c.contract_id, c.contract_no, c.updated_at
--      FROM fgc.insurance_contract c
--      JOIN fgc.batch_watermark w ON w.job_name = 'DailyChangedContractJob'
--     WHERE c.updated_at > w.last_processed_at
--     ORDER BY c.updated_at;
--
-- 3) 아직 처리하지 않은 계약상태 사건도 함께 대상에 넣는다
--    SELECT e.contract_status_event_id, e.contract_id, e.effective_at, e.received_at
--      FROM fgc.contract_status_event e
--     WHERE e.processed_at IS NULL
--     ORDER BY e.effective_at;   -- 수신 순서가 아니라 효력일 순서로 재구성한다
--
-- 4) Step 성공 후 전진 (실패했으면 이 UPDATE를 하지 않는다)
--    UPDATE fgc.batch_watermark
--       SET last_processed_at = :runStartedAt,
--           last_success_at   = now(),
--           last_run_id       = :validationRunId,
--           processed_count   = processed_count + :n
--     WHERE job_name = 'DailyChangedContractJob';
-- ============================================================================
