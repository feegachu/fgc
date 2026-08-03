-- ============================================================================
-- Flyway 마이그레이션 파일 - 자동 생성본. 직접 고치지 마세요.
--
--   원본 : FGC_ERD_Schema/fgc_schema_patch_v2_1_2_to_v2_1_3.sql
--   내용 : patch v2.1.3 - batch_watermark + SCHEDULE_ELIGIBILITY
--
-- 원본과 다른 점 (딱 한 가지)
--   원본에 있던 BEGIN; / COMMIT; 두 줄을 뺐습니다.
--   Flyway가 마이그레이션 하나를 이미 트랜잭션으로 감싸기 때문에,
--   안에서 또 BEGIN/COMMIT 을 하면 Flyway의 트랜잭션이 중간에 끊깁니다.
--   (실패해도 롤백이 안 되고, flyway_schema_history 와 어긋날 수 있습니다)
--
-- 고쳐야 할 일이 생기면 이 파일이 아니라 V3, V4... 를 새로 만드세요.
-- 이미 팀원 DB에 적용된 파일을 고치면 체크섬 오류로 앱이 안 뜹니다.
-- ============================================================================

-- ============================================================================
-- FGC PostgreSQL Schema Patch v2.1.2 -> v2.1.3
-- 대상 DBMS: PostgreSQL 17
-- 작성 기준일: 2026-08-03
--
-- 목적 (2건)
--   1) 일일 변경분 배치가 "어디까지 처리했는지"를 기록할 자리를 만든다.
--   2) policy_type 에 SCHEDULE_ELIGIBILITY 를 추가한다.
--      월중 실효·부활 시 그 달 회차를 줄지 말지 정하는 규칙(STATUS_MONTH_RULE)을
--      코드가 아니라 정책 테이블에서 읽기 위해서다(COR-004).
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

-- ----------------------------------------------------------------------------
-- 3. policy_type 에 SCHEDULE_ELIGIBILITY 추가
--
--    무엇에 쓰나
--      "9월 1일에 실효된 계약의 9월분 유지관리수수료를 줘야 하나?" 같은 판단 규칙을
--      담습니다. 법정 확정 기준이 아직 없어 우리가 정한 값이므로
--      source_class = 'PROJECT_ASSUMPTION' 으로 저장해 화면에 주황 배지가 붙게 합니다.
--
--    ★ 코드에 if (status == 'LAPSED') 처럼 박지 마세요 (COR-004).
--      2026년 말 최종 FAQ가 확정되면 이 정책의 값만 바꾸고 코드는 건드리지 않습니다.
-- ----------------------------------------------------------------------------
-- v2.1.2에서는 이 CHECK가 컬럼에 인라인으로 붙어 있어 이름이 자동 생성되었습니다
-- (policy_version_policy_type_check 형태). 이름을 모르므로 카탈로그에서 찾아 지웁니다.
-- 그냥 ADD만 하면 옛 CHECK가 남아 SCHEDULE_ELIGIBILITY를 계속 거부합니다.
DO $$
DECLARE
  v_name text;
BEGIN
  FOR v_name IN
    SELECT con.conname
      FROM pg_constraint con
      JOIN pg_class    rel ON rel.oid = con.conrelid
      JOIN pg_namespace ns ON ns.oid = rel.relnamespace
     WHERE ns.nspname = 'fgc'
       AND rel.relname = 'policy_version'
       AND con.contype = 'c'
       AND pg_get_constraintdef(con.oid) LIKE '%policy_type%'
  LOOP
    EXECUTE format('ALTER TABLE fgc.policy_version DROP CONSTRAINT %I', v_name);
    RAISE NOTICE '기존 policy_type CHECK 제거: %', v_name;
  END LOOP;
END;
$$;

ALTER TABLE fgc.policy_version ADD CONSTRAINT ck_policy_version_type
  CHECK (policy_type IN (
    'CURRENT_COMMISSION',
    'FOUR_YEAR_COMMISSION',
    'SEVEN_YEAR_COMMISSION',
    'CAP_1200',
    'ALLOCATION',
    'REFUND_RATE',
    'CLAWBACK',
    'RECONCILIATION_TOLERANCE',
    'SCHEDULE_ELIGIBILITY'          -- v2.1.3 신규
  ));

COMMENT ON CONSTRAINT ck_policy_version_type ON fgc.policy_version IS
  'SCHEDULE_ELIGIBILITY = 월중 실효·부활 시 그 달 회차 지급 여부 규칙 (v2.1.3 추가)';

-- 기본 정책 1건 (프로젝트 가정)
--
--   approved_by / created_by 는 app_user(user_id) 를 가리키는 bigint FK 입니다.
--   이 패치는 시드(V3)보다 먼저 도는 마이그레이션이라 app_user 가 아직 비어 있습니다.
--   그래서 NULL 로 둡니다. 시드에서 실제 사용자를 넣은 뒤 UPDATE 하지 않아도 됩니다
--   (ck_policy_approval 은 approved_at 만 요구합니다).
INSERT INTO fgc.policy_version (
    policy_code, policy_name, policy_type, source_class,
    version_no, effective_from, effective_to, status,
    regulation_refs, source_refs, approval_evidence_ref,
    approved_by, approved_at, created_by
) VALUES (
    'STATUS-MONTH-RULE-2026',
    '월중 실효·부활 시 그 달 수수료 처리 기준',
    'SCHEDULE_ELIGIBILITY',
    'PROJECT_ASSUMPTION',            -- 법정 기준 아님. 화면에 주황 배지로 표시된다
    1,
    date '2026-01-01',
    NULL,
    'ACTIVE',
    ARRAY['REG-03','REG-13'],
    ARRAY['docs/05_인터페이스정의서_v2_0.md#9-5'],
    'PROJECT_ASSUMPTION — 2026년 말 최종 FAQ 확정 시 재검증 필요',
    NULL,                            -- app_user 가 아직 없다 (bigint FK)
    now(),                           -- status=ACTIVE 이면 approved_at 필수 (ck_policy_approval)
    NULL
)
ON CONFLICT (policy_code, version_no) DO NOTHING;

/*
  이 정책이 담는 값 3개 (rule_expression 또는 commission_rule.payment_condition_code 로 참조)

  ┌─────────────────────────┬───────────────────────────┬─────────────────────────────────────┐
  │ 파라미터                │ 채택값                    │ 왜 이 값인가                        │
  ├─────────────────────────┼───────────────────────────┼─────────────────────────────────────┤
  │ STATUS_MONTH_RULE       │ PAY_IF_ACTIVE_ON_DUE_DATE │ 대사 매칭키도 회차+지급예정일이라   │
  │                         │                           │ 검증과 대사가 같은 기준을 쓴다      │
  │ PRORATION_RULE          │ NO_PRORATION              │ 유지관리수수료는 매월 동일 금액     │
  │                         │                           │ 이어야 한다 (REG-03)                │
  │ REVIVAL_BACKFILL_RULE   │ NO_RETROACTIVE            │ 실효 기간에는 실제로 유지관리       │
  │                         │                           │ 서비스가 없었다 (REG-03)            │
  └─────────────────────────┴───────────────────────────┴─────────────────────────────────────┘

  지급예정일 시점 계약 상태별 처리
    ACTIVE / REVIVED   → 지급        (schedule_line.line_status = 'CONFIRMED')
    UNPAID             → 보류        ('HOLD')  ※ 미납만으로 영구 중단하지 않는다
    LAPSED             → 미지급      ('CANCELLED')  ※ 부활해도 소급하지 않는다
    TERMINATED/MATURED → 미지급 + 이후 회차 전부 중단
    CANCELLED(청약철회)→ 미지급 + 기지급분은 환수 후보

  자세한 근거: docs/05_인터페이스정의서_v2_0.md §9-5
*/


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
