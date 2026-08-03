-- ============================================================================
-- FGC 보정 마이그레이션 v2.1.3 → v2.1.4
-- 대상 DBMS: PostgreSQL 17
-- 작성 기준일: 2026-08-03
--
-- ── 왜 V2 를 고치지 않고 새 파일을 만드나 ─────────────────────────────────
--   V1·V2 는 이미 팀원 DB에 적용됐을 수 있습니다.
--   이미 적용된 마이그레이션 파일을 고치면 Flyway 가 체크섬 오류를 내고
--   그 사람 앱이 아예 안 뜹니다.
--
--   그래서 V2 는 손대지 않고, 이 파일로 **덧붙여 고칩니다.**
--   파일명이 V2_1 인 이유 — Flyway 는 이걸 버전 "2.1" 로 읽어
--   2 → 2.1 → 3 순서로 적용합니다. V3 시드보다 먼저 들어가야 하기 때문입니다.
--
-- ── 고치는 것 4가지 ───────────────────────────────────────────────────────
--   1) 정책 파라미터를 **실제로 저장할 자리**를 만든다 (policy_parameter)
--      지금은 STATUS_MONTH_RULE 같은 값이 SQL 주석에만 있어서
--      애플리케이션이 DB에서 읽을 수 없습니다. COR-004 위반입니다.
--   2) 그 자리도 ACTIVE 정책이면 못 고치게 잠근다 (기존 트리거 재사용)
--   3) batch_watermark 를 Step 단위로 저장할 수 있게 PK 를 고친다
--   4) 과장된 주석을 정정한다
--
--   ※ V2 가 승인 절차를 건너뛰고 만든 STATUS-MONTH-RULE-2026 정책의 재발행은
--     V3 에서 합니다. 작성자·승인자를 채우려면 app_user 가 먼저 필요하기 때문입니다.
--
-- ── 데이터 영향 ───────────────────────────────────────────────────────────
--   기존 업무 데이터를 지우지 않습니다. 테이블·트리거·제약만 추가·수정합니다.
-- ============================================================================


SET search_path TO fgc, public;

-- ============================================================================
-- 1. policy_parameter — 정책이 담는 "값"을 저장하는 자리
--
--    왜 필요한가
--      policy_version 은 "이 정책이 언제부터 유효한가"만 담습니다.
--      "그래서 값이 뭔데?" 를 담을 곳이 없었습니다.
--      cap_rule_set 처럼 전용 테이블이 있는 정책은 괜찮지만,
--      STATUS_MONTH_RULE 이나 대사 허용오차처럼 값 몇 개짜리 정책은
--      전용 테이블을 만들기엔 과합니다. 그런 것들을 여기에 담습니다.
--
--    ★ 이 값을 자바 코드에 박지 마세요 (COR-004).
--      2026년 말 최종 FAQ 가 확정되면 이 표의 값만 바꾸고 코드는 안 고칩니다.
-- ============================================================================
CREATE TABLE IF NOT EXISTS fgc.policy_parameter (
    policy_parameter_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    policy_version_id   bigint       NOT NULL REFERENCES fgc.policy_version(policy_version_id),
    parameter_key       varchar(80)  NOT NULL,
    parameter_value     jsonb        NOT NULL,
    description         varchar(500),
    created_at          timestamptz  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT uq_policy_parameter UNIQUE (policy_version_id, parameter_key)
);

COMMENT ON TABLE  fgc.policy_parameter IS
  '정책버전이 담는 값. 전용 테이블을 두기엔 작은 규칙들을 키-값으로 저장한다. 코드 하드코딩 금지(COR-004)';
COMMENT ON COLUMN fgc.policy_parameter.parameter_key IS
  '예) STATUS_MONTH_RULE, PRORATION_RULE, TOLERANCE_AMOUNT_KRW, REFUND_RATE_BASIS';
COMMENT ON COLUMN fgc.policy_parameter.parameter_value IS
  'jsonb. 문자열이면 "VALUE", 숫자면 0, 목록이면 ["A","B"] 형태로 저장한다';

CREATE INDEX IF NOT EXISTS ix_policy_parameter_key
  ON fgc.policy_parameter (parameter_key, policy_version_id);

-- ============================================================================
-- 2. 기존 잠금 트리거를 policy_parameter 에도 적용
--
--    ★ 함수를 먼저 고쳐야 합니다.
--      guard_policy_child_mutation() 은 CASE ... ELSE RAISE EXCEPTION 로 끝나서,
--      목록에 없는 테이블에 붙이면 'Unsupported policy child table' 예외가 납니다.
--      그래서 첫 WHEN 목록에 policy_parameter 를 추가한 판으로 교체합니다.
--
--    이렇게 하면 ACTIVE 정책의 파라미터도 다른 자식 행과 똑같이
--    "고치려면 새 정책버전을 만들어라" 가 강제됩니다.
-- ============================================================================
CREATE OR REPLACE FUNCTION fgc.guard_policy_child_mutation()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_old_policy_id bigint;
  v_new_policy_id bigint;
  v_policy_id bigint;
  v_status varchar(20);
BEGIN
  CASE
    -- v2.1.4 : policy_parameter 추가 (policy_version_id 를 직접 갖는 자식들)
    WHEN TG_TABLE_NAME IN ('commission_rule','cap_rule_set','allocation_policy',
                           'refund_rate_table','policy_parameter') THEN
      IF TG_OP <> 'INSERT' THEN v_old_policy_id := OLD.policy_version_id; END IF;
      IF TG_OP <> 'DELETE' THEN v_new_policy_id := NEW.policy_version_id; END IF;
    WHEN TG_TABLE_NAME = 'cap_rule_item' THEN
      IF TG_OP <> 'INSERT' THEN
        SELECT policy_version_id INTO v_old_policy_id
          FROM cap_rule_set WHERE cap_rule_set_id = OLD.cap_rule_set_id
          FOR SHARE;
      END IF;
      IF TG_OP <> 'DELETE' THEN
        SELECT policy_version_id INTO v_new_policy_id
          FROM cap_rule_set WHERE cap_rule_set_id = NEW.cap_rule_set_id
          FOR SHARE;
      END IF;
    WHEN TG_TABLE_NAME = 'refund_rate_line' THEN
      IF TG_OP <> 'INSERT' THEN
        SELECT policy_version_id INTO v_old_policy_id
          FROM refund_rate_table WHERE refund_rate_table_id = OLD.refund_rate_table_id
          FOR SHARE;
      END IF;
      IF TG_OP <> 'DELETE' THEN
        SELECT policy_version_id INTO v_new_policy_id
          FROM refund_rate_table WHERE refund_rate_table_id = NEW.refund_rate_table_id
          FOR SHARE;
      END IF;
    ELSE
      RAISE EXCEPTION 'Unsupported policy child table: %', TG_TABLE_NAME;
  END CASE;

  -- 정책 활성화와 상세행 변경이 동시에 커밋되는 경쟁조건을 막는다.
  -- 동일 문장에서 두 정책을 옮기는 경우에도 작은 ID부터 잠가 교착 가능성을 낮춘다.
  FOR v_policy_id, v_status IN
    SELECT policy_version_id, status
      FROM policy_version
     WHERE policy_version_id = ANY (
       array_remove(ARRAY[v_old_policy_id, v_new_policy_id]::bigint[], NULL)
     )
     ORDER BY policy_version_id
     FOR SHARE
  LOOP
    IF v_status IN ('APPROVED','ACTIVE','RETIRED') THEN
      RAISE EXCEPTION 'Rules under locked policy version % are immutable; create a new policy version', v_policy_id;
    END IF;
  END LOOP;

  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_policy_parameter_policy_lock ON fgc.policy_parameter;
CREATE TRIGGER trg_policy_parameter_policy_lock
BEFORE INSERT OR UPDATE OR DELETE ON fgc.policy_parameter
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_child_mutation();

-- ============================================================================
-- 3. (이 자리에 있던 STATUS-MONTH-RULE-2026 재발행은 V3 로 옮겼습니다)
--
--    이유 — 정책을 낼 때 "누가 만들고 누가 승인했는지"를 채우려면
--    app_user 가 먼저 있어야 하는데, 사용자 시드는 V3 가 넣습니다.
--    여기서 하면 created_by · approved_by 가 전부 NULL 이 되어
--    고치려던 문제(작성자·승인자 없음)를 그대로 반복하게 됩니다.
--
--    그래서 V3 가 사용자를 만든 직후에 재발행합니다.
--    이 파일은 "그럴 수 있는 자리(policy_parameter)와 잠금장치"만 만듭니다.
-- ============================================================================

-- ============================================================================
-- 4. batch_watermark 를 Step 단위로 저장 가능하게
--
--    V2 는 job_name 하나만 PK 로 두면서 step_name 컬럼에는
--    "Step 단위로 나눠 기록할 때 사용" 이라고 적어 두었습니다.
--    실제로는 한 Job 에 Step 이 여러 개면 저장할 수 없습니다. 앞뒤가 안 맞습니다.
--
--    PK 컬럼은 NULL 이 될 수 없으므로, Step 을 나누지 않는 Job 은
--    'JOB' 이라는 고정값을 씁니다.
-- ============================================================================
UPDATE fgc.batch_watermark SET step_name = 'JOB' WHERE step_name IS NULL;

ALTER TABLE fgc.batch_watermark ALTER COLUMN step_name SET DEFAULT 'JOB';
ALTER TABLE fgc.batch_watermark ALTER COLUMN step_name SET NOT NULL;

ALTER TABLE fgc.batch_watermark DROP CONSTRAINT IF EXISTS batch_watermark_pkey;
ALTER TABLE fgc.batch_watermark DROP CONSTRAINT IF EXISTS pk_batch_watermark;
ALTER TABLE fgc.batch_watermark ADD CONSTRAINT pk_batch_watermark
  PRIMARY KEY (job_name, step_name);

COMMENT ON COLUMN fgc.batch_watermark.step_name IS
  'Step 단위 워터마크. Step 을 나누지 않는 Job 은 고정값 JOB 을 쓴다 (PK 는 NULL 불가)';

-- ============================================================================
-- 5. 과장된 주석 정정
--
--    V2 는 "워터마크를 되돌리면 감사로그에 남기는 것으로 통제한다" 고 적었지만,
--    실제 트리거(touch_batch_watermark)는 updated_at 만 갱신합니다.
--    audit_log 에 넣는 코드는 없습니다.
--
--    DB 가 보장하지 않으므로, 애플리케이션이 책임진다는 사실을 명시합니다.
-- ============================================================================
COMMENT ON TABLE fgc.batch_watermark IS
  '배치 Job 이 어디까지 처리했는지 기록하는 기준선. Step 성공 후에만 전진시킨다. '
  '★ 워터마크를 되돌리는 행위의 감사 기록은 DB 가 보장하지 않는다. '
  '애플리케이션 서비스가 audit_log 에 직접 남겨야 한다(action_code=BATCH_WATERMARK_REWIND).';



-- ============================================================================
-- 적용 후 확인
-- ============================================================================
-- -- ① policy_parameter 테이블이 만들어졌는가 (V3 적용 전에는 0행이 정상)
-- SELECT pv.policy_code, pv.version_no, pv.status, pp.parameter_key, pp.parameter_value
--   FROM fgc.policy_parameter pp
--   JOIN fgc.policy_version pv USING (policy_version_id)
--  ORDER BY pv.policy_code, pp.parameter_key;
--
-- -- ② ACTIVE 정책의 파라미터는 못 고쳐야 한다 (아래가 실패해야 정상)
-- UPDATE fgc.policy_parameter SET parameter_value = '"X"'
--  WHERE parameter_key = 'STATUS_MONTH_RULE';
--   → ERROR: Rules under locked policy version N are immutable
--
-- -- ③ 워터마크 PK
-- SELECT job_name, step_name FROM fgc.batch_watermark ORDER BY 1,2;
-- ============================================================================
