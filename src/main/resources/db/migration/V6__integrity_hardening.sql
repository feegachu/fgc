-- ============================================================================
-- FGC 무결성 보강 마이그레이션 v2.1.5 → v2.1.6
-- 대상 DBMS: PostgreSQL 17
-- 작성 기준일: 2026-08-05
-- 결정 근거: SRC-027 FGC 정합성 설계결정서 §3-2 (D-01·D-04)
--
-- ── 왜 V1~V5 를 고치지 않고 V6 를 만드나 ──────────────────────────────────
--   V1·V2·V2.1·V3·V4 는 이미 팀원 DB에 적용돼 있습니다.
--   적용된 마이그레이션 파일을 한 글자라도 고치면 Flyway 가 체크섬 오류를 내고
--   그 사람 앱이 아예 안 뜹니다. 그래서 전부 이 파일로 덧붙여 고칩니다.
--
--   ★ 이 파일은 db/migration 에 둡니다. db/demo 가 아닙니다.
--     운영은 classpath:db/migration 만 읽고, 로컬만 db/demo 를 더 읽습니다.
--     따라서 V6 은 V3·V4 시드가 있다고 가정하면 안 됩니다.
--     빈 운영 DB에도, 시드가 다 들어간 로컬 DB에도 똑같이 적용돼야 합니다.
--
-- ── 고치는 것 5가지 ───────────────────────────────────────────────────────
--   1) P0-1 contract_status_event.processed_at 이 원리적으로 갱신 불가능한 문제
--          → 처리이력 전용 테이블을 새로 만든다
--   2) P0-2 잔여 : 정책 생명주기를 INSERT 로 우회할 수 있는 문제
--          → INSERT 가드 + 작성자·승인자 분리 CHECK
--   3) P1-3 검증·대사 실행의 상태전이가 아무 데서나 점프되는 문제
--          → 두 테이블이 함께 쓰는 상태전이 가드 1개
--   4) D-01 acquisition_cost_check 에 사용률을 담을 자리가 없는 문제
--          → 검증유형별 상세행 테이블 (2차 기능용 물리 선반영, D-03)
--   5) P0-4 트리거 함수가 실행시점 search_path 에 의존하는 문제
--          → fgc 스키마 함수 전체에 search_path 를 못 박는다 (맨 마지막에 실행)
--
-- ── 데이터 영향 ───────────────────────────────────────────────────────────
--   기존 행을 지우거나 값을 바꾸지 않습니다.
--   테이블 2개·함수 2개·트리거 5개·제약 1개(NOT VALID)를 추가하고,
--   기존 함수 1개를 확장합니다.
-- ============================================================================

SET search_path TO fgc, public;


-- ============================================================================
-- 1. P0-1 : 계약상태 사건의 "처리했다" 기록을 담을 자리
--
--    무엇이 문제였나
--      V1 은 contract_status_event 에 processed_at 컬럼을 두고(V1:662),
--      같은 테이블에 append-only 트리거(UPDATE·DELETE 전면 금지)를 붙였습니다(V1:677).
--      그래서 사건이 들어온 뒤에 배치가 processed_at 을 채우는 것이 불가능합니다.
--      그런데 V2 의 적용후확인 주석(V2:243-247)은 WHERE processed_at IS NULL 로
--      미처리 사건을 고르라고 안내합니다. 서로 모순입니다.
--
--    왜 컬럼을 UPDATE 가능하게 푸는 대신 표를 새로 만드나
--      ① append-only 를 푸는 순간 "사건 원본은 절대 안 바뀐다"는 규제 근거가 무너집니다.
--         사건 원본과 우리 처리기록은 성격이 다른 사실이므로 표를 나누는 게 맞습니다.
--      ② 한 사건을 여러 Job 이 봅니다. DailyChangedContractJob 도, MonthlyValidationJob 도.
--         컬럼 하나로는 "어느 Job 이 처리했나"를 구분할 수 없습니다.
--      ③ 실패도 증거입니다. 실패 → 재시도 → 성공의 전 과정이 남아야 합니다.
--
--    ★ 멱등성 설계 (V2 헤더가 "UNIQUE 제약이 중복을 막아 준다"고 명시한 그 성질)
--      PK 는 대리키로 둡니다. 자연키를 PK 로 두면 실패 이력을 못 쌓습니다.
--      대신 부분 UNIQUE 인덱스로 "사건 × Job 당 SUCCEEDED 는 최대 1행"을 강제합니다.
--        - 실패(FAILED)는 몇 번이든 쌓인다        → 증거 보존
--        - 성공(SUCCEEDED)은 Job 당 딱 한 번      → 배치 재실행해도 중복 없음
-- ============================================================================
CREATE TABLE IF NOT EXISTS fgc.contract_status_event_processing (
    contract_status_event_processing_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_status_event_id bigint       NOT NULL
                             REFERENCES fgc.contract_status_event(contract_status_event_id),
    processing_job           varchar(100) NOT NULL,
    processing_status        varchar(20)  NOT NULL
                             CHECK (processing_status IN ('SUCCEEDED','FAILED')),
    validation_run_id        bigint REFERENCES fgc.validation_run(validation_run_id),
    processed_at             timestamptz  NOT NULL DEFAULT clock_timestamp(),
    failure_reason           varchar(2000),
    created_at               timestamptz  NOT NULL DEFAULT clock_timestamp(),
    CONSTRAINT ck_cse_processing_failure
      CHECK ((processing_status = 'FAILED') = (failure_reason IS NOT NULL))
);

-- 사건 × Job 당 성공기록은 하나뿐. 실패기록은 제한 없음.
CREATE UNIQUE INDEX IF NOT EXISTS uq_cse_processing_succeeded
  ON fgc.contract_status_event_processing (contract_status_event_id, processing_job)
  WHERE processing_status = 'SUCCEEDED';

-- 사건 하나의 처리 전말(실패 포함)을 시간순으로 뽑는 경로. 이 표의 주 용도입니다.
CREATE INDEX IF NOT EXISTS ix_cse_processing_event
  ON fgc.contract_status_event_processing (contract_status_event_id, processed_at);

COMMENT ON TABLE fgc.contract_status_event_processing IS
  '계약상태 사건을 어느 배치가 언제 처리했는지의 기록. 사건 원본(append-only)과 분리한다. '
  '미처리 사건 조회는 NOT EXISTS 를 쓴다(V2:243-247 의 processed_at IS NULL 을 대체). '
  '★ 뷰를 만들지 않은 이유 — 올바른 쿼리는 반드시 processing_job = :jobName 을 포함해야 하는데 '
  '뷰는 파라미터를 못 받아, Daily 가 처리한 사건을 Monthly 가 처리됨으로 오인하는 함정이 된다.';
COMMENT ON COLUMN fgc.contract_status_event_processing.processing_job IS
  'Spring Batch Job 이름. 예) DailyChangedContractJob, MonthlyValidationJob. '
  'CHECK 를 걸지 않는다 — Job 이 늘 때마다 마이그레이션을 새로 만들어야 하기 때문(COR-004)';
COMMENT ON COLUMN fgc.contract_status_event_processing.processing_status IS
  'SUCCEEDED = 해당 Job 기준 처리 완료. FAILED = 실패, failure_reason 필수';
COMMENT ON COLUMN fgc.contract_status_event_processing.validation_run_id IS
  '그때 만들어진 validation_run. 일일 보정처럼 run 없이 도는 처리는 NULL';
COMMENT ON COLUMN fgc.contract_status_event_processing.processed_at IS
  '처리를 마친 시각. ★ 사건의 received_at 이후여야 한다는 규칙은 DB가 강제하지 못한다 '
  '(부모 테이블 컬럼이라 CHECK 로 표현 불가). 애플리케이션이 지킨다';

-- 처리기록도 사건 원본과 같이 고쳐 쓸 수 없게 한다.
-- 실패와 성공이 각각 "다른 행"이므로 UPDATE 할 일이 원래 없다.
-- 고쳐 쓸 수 있는 처리기록은 그것이 설명하는 사건보다 증거력이 약해진다.
DROP TRIGGER IF EXISTS trg_cse_processing_append_only ON fgc.contract_status_event_processing;
CREATE TRIGGER trg_cse_processing_append_only
BEFORE UPDATE OR DELETE ON fgc.contract_status_event_processing
FOR EACH ROW EXECUTE FUNCTION fgc.reject_update_delete();

-- ── contract_status_event.processed_at 은 어떻게 하나 : 남긴다 ──────────────
--   지우면 다음을 잃습니다.
--     ① 팀원 DB·운영 DB에 이미 들어 있는 실제 값. append-only 로 지키던 감사 데이터를
--        마이그레이션이 스스로 파괴하는 셈이고, 되돌릴 방법이 없습니다.
--     ② V3 §15 가 "effective_at / received_at / processed_at 세 날짜를 구분하라"고
--        가르치는 시드 시나리오가 통째로 깨집니다.
--   지워서 얻는 것은 NULL 비트 한 자리뿐입니다. 남기고 주석으로 폐기 표시합니다.
--   (ck_contract_status_processed CHECK(V1:674)는 그대로 유효하며 아무것도 막지 않습니다)
COMMENT ON COLUMN fgc.contract_status_event.processed_at IS
  '【DEPRECATED v2.1.6】신규 코드에서 읽지도 쓰지도 마세요. '
  'append-only 트리거 때문에 INSERT 시점에만 넣을 수 있고 나중에 갱신할 수 없습니다. '
  '처리 여부는 fgc.contract_status_event_processing 을 보세요. '
  '컬럼을 지우지 않는 이유 — 기존 DB에 들어 있는 값은 되살릴 수 없는 감사 데이터입니다.';


-- ============================================================================
-- 2. P0-2 잔여 : 정책 생명주기를 INSERT 로 우회하는 문제
--
--    무엇이 문제였나
--      guard_policy_version_lifecycle 은 BEFORE UPDATE OR DELETE 입니다(V1:534-536).
--      그래서 INSERT ... status='ACTIVE' 는 그냥 통과합니다.
--      ck_policy_approval(V1:327-329)도 approved_at 만 요구해서
--      created_by·approved_by 가 NULL 이어도 ACTIVE 정책이 만들어집니다.
--      실제로 V2 가 그 경로로 STATUS-MONTH-RULE-2026 v1 을 넣었습니다(V2:182-203).
--      데이터는 V3 이 폐기·재발행으로 수습했지만, 강제장치는 그대로 뚫려 있습니다.
-- ============================================================================

-- ── 2-1. 작성자·승인자 분리 강제 (REG-22) ─────────────────────────────────
--
--   ★ 왜 NOT VALID 인가 — 반드시 읽어야 합니다
--     V2 가 넣은 STATUS-MONTH-RULE-2026 v1 은 DB마다 상태가 다릅니다.
--       · 로컬(V3 적용됨) : status='RETIRED', created_by=NULL, approved_by=NULL
--       · 운영(V3 미적용) : status='ACTIVE',  created_by=NULL, approved_by=NULL
--     즉 운영 DB에는 지금도 작성자 없는 ACTIVE 정책이 살아 있을 수 있습니다.
--     그냥 ADD CONSTRAINT 하면 그 DB에 V6 적용이 실패합니다.
--
--   ★ 왜 값을 채워 넣지(backfill) 않았나
--     운영 DB의 app_user 는 비어 있습니다(사용자 시드는 V3 = demo 전용).
--     작성자를 넣으려면 가짜 시스템 계정을 만들어야 하는데,
--     없는 승인자를 있는 것처럼 적는 것은 없는 것보다 나쁜 증거입니다.
--     사실대로 "이 한 행은 절차 이전의 유산"으로 남기고 예외임을 명시합니다.
--
--   ★ 왜 RETIRED 를 대상에서 뺐나
--     ① V6 이후로는 RETIRED 에 도달하려면 반드시 APPROVED 나 ACTIVE 를 거치는데
--        그 두 상태는 이 CHECK 가 검사합니다. 신규 RETIRED 행은 이미 검증된 것입니다.
--     ② 운영 DB의 유산 행을 나중에 정상적으로 폐기할 길을 열어 둡니다.
--        RETIRED 까지 검사하면 그 UPDATE 가 CHECK 에 걸려(NOT VALID 라도 UPDATE 는 검사됨)
--        유산 행이 영영 폐기 불가능해집니다. 고치라고 만든 제약이 고치는 걸 막는 셈입니다.
--
--   ★ VALIDATE 는 언제 하나
--     유산 행을 RETIRED 로 폐기하고 v2 를 절차대로 발행한 다음,
--     별도 마이그레이션에서 아래 한 줄을 돌립니다. 지금 하면 안 됩니다.
--       ALTER TABLE fgc.policy_version VALIDATE CONSTRAINT ck_policy_authorship;
ALTER TABLE fgc.policy_version DROP CONSTRAINT IF EXISTS ck_policy_authorship;
ALTER TABLE fgc.policy_version ADD CONSTRAINT ck_policy_authorship CHECK (
    status NOT IN ('APPROVED','ACTIVE')
    OR (created_by   IS NOT NULL
    AND approved_by  IS NOT NULL
    AND approved_at  IS NOT NULL
    AND created_by  <> approved_by)
) NOT VALID;

COMMENT ON CONSTRAINT ck_policy_authorship ON fgc.policy_version IS
  '승인·활성 정책은 작성자와 승인자가 모두 있어야 하고 서로 달라야 한다(REG-22, SRC-027). '
  'NOT VALID 인 이유 — V2 가 절차를 건너뛰고 만든 STATUS-MONTH-RULE-2026 v1 이 '
  'V3(demo)를 적용하지 않은 DB에는 아직 ACTIVE 로 남아 있다. 그 행을 폐기한 뒤 VALIDATE 한다. '
  'RETIRED 를 제외한 이유 — 유산 행의 폐기 경로를 막지 않기 위해서다.';

-- ── 2-2. INSERT 도 생명주기 가드에 태운다 ─────────────────────────────────
--
--   V1 본체를 그대로 두고 맨 앞에 INSERT 분기만 얹습니다.
--   나머지(삭제 제한·불변 컬럼·상태전이표)는 V1:494-536 과 한 글자도 다르지 않습니다.
--
--   V3·V4 를 깨뜨리지 않는가 — 확인했습니다.
--     V3 §1-1②(V3:98) 와 §7(V3:303-306) 은 둘 다 'DRAFT' 로 INSERT 합니다.
--     V4 에는 policy_version INSERT 자체가 없습니다.
--     V2 의 ACTIVE INSERT 는 V6 보다 먼저 끝났으므로 영향받지 않습니다.
CREATE OR REPLACE FUNCTION fgc.guard_policy_version_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  -- v2.1.6 신규 : INSERT 우회 차단
  IF TG_OP = 'INSERT' THEN
    IF NEW.status <> 'DRAFT' THEN
      RAISE EXCEPTION
        'policy_version must be inserted as DRAFT (got %); promote with DRAFT -> APPROVED -> ACTIVE updates',
        NEW.status;
    END IF;
    RETURN NEW;
  END IF;

  IF TG_OP = 'DELETE' THEN
    IF OLD.status <> 'DRAFT' THEN
      RAISE EXCEPTION 'Only DRAFT policy versions may be deleted; create a new version instead';
    END IF;
    RETURN OLD;
  END IF;

  IF OLD.status IN ('APPROVED','ACTIVE','RETIRED') THEN
    IF ROW(NEW.policy_code, NEW.policy_name, NEW.policy_type, NEW.version_no,
           NEW.source_class, NEW.effective_from, NEW.effective_to, NEW.fee_regime_code,
           NEW.basic_document_version, NEW.regulation_refs, NEW.source_refs,
           NEW.approval_evidence_ref, NEW.approved_by, NEW.approved_at, NEW.created_by)
       IS DISTINCT FROM
       ROW(OLD.policy_code, OLD.policy_name, OLD.policy_type, OLD.version_no,
           OLD.source_class, OLD.effective_from, OLD.effective_to, OLD.fee_regime_code,
           OLD.basic_document_version, OLD.regulation_refs, OLD.source_refs,
           OLD.approval_evidence_ref, OLD.approved_by, OLD.approved_at, OLD.created_by) THEN
      RAISE EXCEPTION 'APPROVED/ACTIVE/RETIRED policy version is immutable; create a new version';
    END IF;
  END IF;

  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
      (OLD.status = 'DRAFT'    AND NEW.status IN ('REVIEW','APPROVED')) OR
      (OLD.status = 'REVIEW'   AND NEW.status IN ('DRAFT','APPROVED')) OR
      (OLD.status = 'APPROVED' AND NEW.status IN ('ACTIVE','RETIRED')) OR
      (OLD.status = 'ACTIVE'   AND NEW.status = 'RETIRED')
    ) THEN
      RAISE EXCEPTION 'Invalid policy status transition: % -> %', OLD.status, NEW.status;
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_policy_version_lifecycle ON fgc.policy_version;
CREATE TRIGGER trg_policy_version_lifecycle
BEFORE INSERT OR UPDATE OR DELETE ON fgc.policy_version
FOR EACH ROW EXECUTE FUNCTION fgc.guard_policy_version_lifecycle();


-- ============================================================================
-- 3. P1-3 : 실행(run) 상태전이가 아무 데서나 점프되는 문제
--
--    무엇이 문제였나
--      guard_validation_run_finalized(V1:1633-1649) 와
--      guard_reconciliation_run_finalized(V1:1715-1728) 는
--      "FINALIZED 가 된 뒤" 만 잠급니다. 그 전에는 무엇이든 됩니다.
--      CREATED → FINALIZED 직행도, FAILED → FINALIZED 도 통과합니다.
--      돌지도 않은 검증이 확정되면 그 확정에는 근거가 없습니다.
--
--    두 테이블은 상태 어휘가 같고(CREATED/RUNNING/COMPLETED/FAILED/FINALIZED)
--    PK 컬럼 이름만 다릅니다. 그래서 함수는 하나만 만들고
--    PK 컬럼명을 TG_ARGV[0] 으로 넘겨 오류 메시지에만 씁니다.
--    (to_jsonb 는 실패 분기 안에서만 부릅니다. 정상 UPDATE 에는 비용이 없습니다)
--
--    ★ 기존 FINALIZED 가드는 건드리지 않고 트리거를 나란히 둡니다.
--      ① 이미 검증된 불변성 로직을 다시 쓰다가 후퇴시킬 위험이 없습니다.
--      ② PostgreSQL 은 같은 시점 트리거를 이름 알파벳순으로 실행합니다.
--         trg_*_finalized < trg_*_lifecycle 이므로
--         FINALIZED 행은 전이 검사에 도달하기도 전에 먼저 거부됩니다.
--         (그래서 아래 전이표에 FINALIZED 출발 항목이 없어도 안전합니다)
--
--    시드 영향 — 확인했습니다.
--      V3·V4 어디에도 validation_run / reconciliation_run INSERT 가 없습니다.
--      따라서 새 DB에서 V3·V4 를 다시 돌려도 이 트리거에 걸릴 일이 없습니다.
-- ============================================================================
CREATE OR REPLACE FUNCTION fgc.guard_run_lifecycle()
RETURNS trigger
LANGUAGE plpgsql
AS $$
BEGIN
  IF NEW.status IS DISTINCT FROM OLD.status THEN
    IF NOT (
      (OLD.status = 'CREATED'   AND NEW.status = 'RUNNING') OR
      (OLD.status = 'RUNNING'   AND NEW.status IN ('COMPLETED','FAILED')) OR
      (OLD.status = 'COMPLETED' AND NEW.status = 'FINALIZED') OR
      (OLD.status = 'FAILED'    AND NEW.status = 'RUNNING')
    ) THEN
      RAISE EXCEPTION 'Invalid % status transition: % -> % (run %)',
        TG_TABLE_NAME, OLD.status, NEW.status, to_jsonb(OLD) ->> TG_ARGV[0];
    END IF;
  END IF;
  RETURN NEW;
END;
$$;

COMMENT ON FUNCTION fgc.guard_run_lifecycle() IS
  'validation_run·reconciliation_run 공용 상태전이 가드(SRC-027 P1-3). '
  '허용 : CREATED→RUNNING, RUNNING→COMPLETED, RUNNING→FAILED, COMPLETED→FINALIZED, FAILED→RUNNING. '
  '트리거 인자로 PK 컬럼명을 넘긴다(오류 메시지 전용). '
  'FINALIZED 이후 불변성은 기존 guard_*_run_finalized 트리거가 담당한다(중복 구현 금지)';

DROP TRIGGER IF EXISTS trg_validation_run_lifecycle ON fgc.validation_run;
CREATE TRIGGER trg_validation_run_lifecycle
BEFORE UPDATE ON fgc.validation_run
FOR EACH ROW EXECUTE FUNCTION fgc.guard_run_lifecycle('validation_run_id');

DROP TRIGGER IF EXISTS trg_reconciliation_run_lifecycle ON fgc.reconciliation_run;
CREATE TRIGGER trg_reconciliation_run_lifecycle
BEFORE UPDATE ON fgc.reconciliation_run
FOR EACH ROW EXECUTE FUNCTION fgc.guard_run_lifecycle('reconciliation_run_id');


-- ============================================================================
-- 4. D-01 : 계약체결비용 검증의 사용률 4개를 담을 자리
--
--    무엇이 문제였나
--      운영정책서 제45조(:924-929)는 사용률 4개를 각각 저장하라고 정합니다.
--        선지급 총액   ÷ 계약체결비용
--        유지관리 월액 ÷ (계약체결비용 × 월 상한율)
--        유지관리 총액 ÷ 계약체결비용
--        장기유지 월액 ÷ (계약체결비용 × 0.4%)
--      그런데 acquisition_cost_check(V1:1859-1873)에는 사용률 컬럼이 하나도 없습니다.
--      금액 쌍만 있습니다.
--
--    왜 컬럼을 4개 더 붙이지 않고 상세행 테이블인가
--      월액 두 유형(유지관리 월액·장기유지 월액)은 해당 월마다 값이 다릅니다.
--      컬럼으로는 한 달치밖에 못 담습니다. 검증유형 × 대상월 = 1행이어야 합니다.
--
--    ★ 이 표는 2차 기능용 물리 선반영입니다 (SRC-027 D-03).
--      표가 있다는 것이 계약체결비용 검증의 1차 제공을 뜻하지 않습니다.
--      1차에는 비활성이며 ACQC-W01 화면과 함께 2차에 켭니다.
--
--    ★ 초년도 1,200% 는 여기 넣지 않습니다 (SRC-027 D-01).
--      근거 조문(REG-08)도, 판정 기준금액(월납환산 초회보험료 × 12)도,
--      저장 테이블(cap_check)도 다른 별개의 규제검증입니다.
-- ============================================================================
CREATE TABLE IF NOT EXISTS fgc.acquisition_cost_check_detail (
    acquisition_cost_check_detail_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    acquisition_cost_check_id bigint       NOT NULL
                              REFERENCES fgc.acquisition_cost_check(acquisition_cost_check_id),
    check_type                varchar(30)  NOT NULL CHECK (check_type IN (
                                'UPFRONT_TOTAL',        -- 선지급 총액   (REG-02)
                                'MAINTENANCE_MONTHLY',  -- 유지관리 월액 (REG-03)
                                'MAINTENANCE_TOTAL',    -- 유지관리 총액 (REG-03 제4호)
                                'LONG_TERM_MONTHLY'     -- 장기유지 월액 (REG-05)
                              )),
    target_month              date CHECK (target_month IS NULL OR fgc.is_first_day_of_month(target_month)),
    actual_amount             numeric(15,2) NOT NULL CHECK (actual_amount >= 0),
    limit_amount              numeric(15,2) NOT NULL CHECK (limit_amount >= 0),
    usage_pct                 numeric(12,6),
    result_status             varchar(20)  NOT NULL
                              CHECK (result_status IN ('NORMAL','WARNING','VIOLATION','REVIEW_REQUIRED')),
    decision_reason           varchar(1000) NOT NULL,
    calculation_snapshot      jsonb        NOT NULL DEFAULT '{}'::jsonb,
    created_at                timestamptz  NOT NULL DEFAULT clock_timestamp(),
    -- 월액 유형은 대상월이 반드시 있고, 총액 유형은 반드시 없다.
    CONSTRAINT ck_acqd_target_month CHECK (
      (check_type IN ('MAINTENANCE_MONTHLY','LONG_TERM_MONTHLY')) = (target_month IS NOT NULL)
    )
);

-- 총액 유형은 검증당 1행. 월액 유형은 대상월당 1행.
-- COALESCE 로 NULL 을 무한과거로 치환해 총액 유형도 같은 인덱스로 막는다.
CREATE UNIQUE INDEX IF NOT EXISTS uq_acquisition_cost_check_detail
  ON fgc.acquisition_cost_check_detail (
    acquisition_cost_check_id, check_type, COALESCE(target_month, '-infinity'::date)
  );

COMMENT ON TABLE fgc.acquisition_cost_check_detail IS
  '계약체결비용 검증의 유형별 사용률 상세(운영정책서 제45조, SRC-027 D-01). '
  '네 한도는 서로 차감하지 않는다. 하나라도 100%를 넘으면 부모가 VIOLATION 이다. '
  '★ 2차 기능용 물리 선반영 — 표의 존재가 1차 제공을 뜻하지 않는다(SRC-027 D-03). '
  '★ 초년도 1,200%(REG-08)는 여기 넣지 않는다. cap_check 가 담당하는 별개 검증이다.';
COMMENT ON COLUMN fgc.acquisition_cost_check_detail.check_type IS
  'UPFRONT_TOTAL=선지급 총액(REG-02) / MAINTENANCE_MONTHLY=유지관리 월액(REG-03) / '
  'MAINTENANCE_TOTAL=유지관리 총액(REG-03 제4호) / LONG_TERM_MONTHLY=장기유지 월액(REG-05)';
COMMENT ON COLUMN fgc.acquisition_cost_check_detail.target_month IS
  '월액 유형의 대상 정산월(1일). 총액 유형은 NULL. ck_acqd_target_month 가 강제한다';
COMMENT ON COLUMN fgc.acquisition_cost_check_detail.usage_pct IS
  'actual_amount ÷ limit_amount × 100. 사용률 정밀도는 소수점 6자리(cap_check.usage_pct 와 동일)';

-- ── 부모와 동일하게 FINALIZED 실행의 결과를 잠근다 ─────────────────────────
--   guard_finalized_validation_result 는 CASE ... ELSE RAISE EXCEPTION 으로 끝나서
--   목록에 없는 테이블에 붙이면 'Unsupported validation result table' 예외가 납니다.
--   그래서 cap_check_detail 분기와 똑같은 모양으로 한 갈래를 더한 판으로 교체합니다.
--   (V2_1 이 guard_policy_child_mutation 에 policy_parameter 를 더한 것과 같은 방식)
--   나머지 로직은 V1:1570-1618 과 한 글자도 다르지 않습니다.
CREATE OR REPLACE FUNCTION fgc.guard_finalized_validation_result()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
  v_old_run_id bigint;
  v_new_run_id bigint;
  v_parent_id bigint;
  v_run_id bigint;
  v_status varchar(20);
BEGIN
  IF TG_TABLE_NAME IN ('validation_target','cap_check','arbitrage_check','acquisition_cost_check','maintenance_check') THEN
    IF TG_OP <> 'INSERT' THEN v_old_run_id := OLD.validation_run_id; END IF;
    IF TG_OP <> 'DELETE' THEN v_new_run_id := NEW.validation_run_id; END IF;
  ELSIF TG_TABLE_NAME = 'cap_check_detail' THEN
    IF TG_OP <> 'INSERT' THEN
      v_parent_id := OLD.cap_check_id;
      SELECT validation_run_id INTO v_old_run_id
        FROM cap_check WHERE cap_check_id = v_parent_id
        FOR SHARE;
    END IF;
    IF TG_OP <> 'DELETE' THEN
      v_parent_id := NEW.cap_check_id;
      SELECT validation_run_id INTO v_new_run_id
        FROM cap_check WHERE cap_check_id = v_parent_id
        FOR SHARE;
    END IF;
  -- v2.1.6 신규 : acquisition_cost_check_detail (부모를 거쳐 run 을 찾는다)
  ELSIF TG_TABLE_NAME = 'acquisition_cost_check_detail' THEN
    IF TG_OP <> 'INSERT' THEN
      v_parent_id := OLD.acquisition_cost_check_id;
      SELECT validation_run_id INTO v_old_run_id
        FROM acquisition_cost_check WHERE acquisition_cost_check_id = v_parent_id
        FOR SHARE;
    END IF;
    IF TG_OP <> 'DELETE' THEN
      v_parent_id := NEW.acquisition_cost_check_id;
      SELECT validation_run_id INTO v_new_run_id
        FROM acquisition_cost_check WHERE acquisition_cost_check_id = v_parent_id
        FOR SHARE;
    END IF;
  ELSE
    RAISE EXCEPTION 'Unsupported validation result table: %', TG_TABLE_NAME;
  END IF;

  -- 결과행 변경과 validation_run FINALIZED 전환을 직렬화한다.
  FOR v_run_id, v_status IN
    SELECT validation_run_id, status
      FROM validation_run
     WHERE validation_run_id = ANY (
       array_remove(ARRAY[v_old_run_id, v_new_run_id]::bigint[], NULL)
     )
     ORDER BY validation_run_id
     FOR SHARE
  LOOP
    IF v_status = 'FINALIZED' THEN
      RAISE EXCEPTION 'Results of finalized validation run % are immutable', v_run_id;
    END IF;
  END LOOP;

  IF TG_OP='DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END;
$$;

DROP TRIGGER IF EXISTS trg_acquisition_cost_check_detail_immutable
  ON fgc.acquisition_cost_check_detail;
CREATE TRIGGER trg_acquisition_cost_check_detail_immutable
BEFORE INSERT OR UPDATE OR DELETE ON fgc.acquisition_cost_check_detail
FOR EACH ROW EXECUTE FUNCTION fgc.guard_finalized_validation_result();


-- ============================================================================
-- 5. P0-4 : 함수가 실행시점 search_path 에 의존하는 문제  ★ 반드시 맨 마지막
--
--    무엇이 문제였나
--      V1 의 함수 다수가 테이블을 스키마 없이 씁니다.
--        FROM policy_version / FROM validation_run / FROM commission_transaction ...
--      이 이름은 함수를 만들 때가 아니라 "부를 때"의 search_path 로 풀립니다.
--      커넥션풀 설정이 바뀌거나, 누가 fgc 앞에 다른 스키마를 끼워 넣거나,
--      psql 에서 search_path 를 바꾸고 UPDATE 하면
--      가드가 엉뚱한 테이블을 보고 조용히 통과합니다. 막으라고 만든 가드가요.
--
--    ★ 왜 함수 본문을 고치지 않고 ALTER FUNCTION ... SET 인가
--      본문 수정은 23개 함수를 전부 다시 써야 하고 그 과정에서 로직이 바뀔 위험이 있습니다.
--      ALTER 는 로직을 한 글자도 건드리지 않고 이름 해석만 고정합니다.
--
--    ★ 왜 개별 ALTER 나열 대신 DO 루프인가 (둘 다 검토했습니다)
--      나열하면 23줄 + 인자 시그니처를 손으로 맞춰야 합니다.
--      is_within_first_year(date,date) 같은 걸 하나만 틀려도 마이그레이션 전체가 실패합니다.
--      루프는 pg_get_function_identity_arguments 로 카탈로그에서 정확한 시그니처를
--      직접 읽으므로 틀릴 수가 없고, 앞으로 함수가 늘어도 이 파일을 안 고쳐도 됩니다.
--
--    ★ IMMUTABLE 함수는 왜 뺐나 (여기가 유일한 예외입니다)
--      is_first_day_of_month / round_krw_half_up / is_within_first_year 3개입니다.
--      셋 다 테이블을 전혀 참조하지 않아 search_path 와 무관합니다.
--      반면 SET 절이 붙은 SQL 함수는 플래너가 인라인 전개를 못 합니다.
--      is_first_day_of_month 는 statement_batch·commission_transaction·
--      transaction_attribution·validation_run·reconciliation_run 의
--      CHECK 제약에서 행마다 호출됩니다. 얻는 것 0, 잃는 것만 있는 거래라 뺍니다.
--
--    ★ 실행 순서 — 이 절이 파일 맨 뒤에 있는 이유
--      위에서 새로 만든 guard_run_lifecycle 과 교체한
--      guard_policy_version_lifecycle·guard_finalized_validation_result 까지
--      한 번에 덮기 위해서입니다.
-- ============================================================================
DO $$
DECLARE
  r record;
BEGIN
  FOR r IN
    SELECT p.proname,
           pg_get_function_identity_arguments(p.oid) AS args
      FROM pg_proc p
      JOIN pg_namespace n ON n.oid = p.pronamespace
     WHERE n.nspname = 'fgc'
       AND p.prokind = 'f'
       AND p.provolatile <> 'i'      -- IMMUTABLE 순수함수는 인라인 유지를 위해 제외
     ORDER BY p.proname
  LOOP
    EXECUTE format('ALTER FUNCTION fgc.%I(%s) SET search_path = fgc, pg_temp',
                   r.proname, r.args);
    RAISE NOTICE 'search_path 고정: fgc.%(%)', r.proname, r.args;
  END LOOP;
END;
$$;


-- ============================================================================
-- 적용 후 확인
-- ============================================================================
-- ① search_path 가 안 박힌 함수가 남았는가 (IMMUTABLE 3개만 나와야 정상)
--    SELECT p.proname, p.provolatile, p.proconfig
--      FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace
--     WHERE n.nspname='fgc' AND p.prokind='f' AND p.proconfig IS NULL
--     ORDER BY 1;
--    → is_first_day_of_month / is_within_first_year / round_krw_half_up
--
-- ② 정책 INSERT 우회가 막혔는가 (아래가 실패해야 정상)
--    INSERT INTO fgc.policy_version
--      (policy_code,policy_name,policy_type,source_class,version_no,
--       effective_from,status,approved_at)
--    VALUES ('TEST-BYPASS','우회시도','CAP_1200','GA_POLICY',1,
--            current_date,'ACTIVE',now());
--    → ERROR: policy_version must be inserted as DRAFT (got ACTIVE)
--
-- ③ 승인자 = 작성자 인 승격이 막혔는가 (두 번째 UPDATE 가 실패해야 정상)
--    INSERT INTO fgc.policy_version
--      (policy_code,policy_name,policy_type,source_class,version_no,
--       effective_from,status,created_by)
--    VALUES ('TEST-SELFAPPROVE','자가승인','CAP_1200','GA_POLICY',1,
--            current_date,'DRAFT',(SELECT user_id FROM fgc.app_user LIMIT 1));
--    UPDATE fgc.policy_version SET status='APPROVED', approved_at=now(),
--           approved_by=created_by
--     WHERE policy_code='TEST-SELFAPPROVE';
--    → ERROR: ... violates check constraint "ck_policy_authorship"
--    (정리) DELETE FROM fgc.policy_version WHERE policy_code LIKE 'TEST-%';
--
-- ④ 실행 상태 점프가 막혔는가 (두 번째가 실패, 세 번째는 성공해야 정상)
--    INSERT INTO fgc.validation_run(validation_month, run_no)
--    VALUES (date_trunc('month', current_date)::date, 999);
--    UPDATE fgc.validation_run SET status='FINALIZED', finalized_at=now()
--     WHERE run_no=999;
--    → ERROR: Invalid validation_run status transition: CREATED -> FINALIZED (run N)
--    UPDATE fgc.validation_run SET status='RUNNING', started_at=now() WHERE run_no=999;
--    → 성공
--    (정리) DELETE FROM fgc.validation_run WHERE run_no=999;
--
-- ⑤ 미처리 계약상태 사건 조회 (V2:243-247 의 processed_at IS NULL 을 대체)
--    SELECT e.contract_status_event_id, e.contract_id, e.effective_at, e.received_at
--      FROM fgc.contract_status_event e
--     WHERE NOT EXISTS (
--             SELECT 1 FROM fgc.contract_status_event_processing p
--              WHERE p.contract_status_event_id = e.contract_status_event_id
--                AND p.processing_job    = 'DailyChangedContractJob'
--                AND p.processing_status = 'SUCCEEDED')
--     ORDER BY e.effective_at;   -- 수신 순서가 아니라 효력일 순서로 재구성한다
--
-- ⑥ 처리기록 멱등성 (같은 문장을 두 번 돌려도 1행이어야 정상)
--    INSERT INTO fgc.contract_status_event_processing
--      (contract_status_event_id, processing_job, processing_status)
--    SELECT contract_status_event_id, 'DailyChangedContractJob', 'SUCCEEDED'
--      FROM fgc.contract_status_event ORDER BY contract_status_event_id LIMIT 1
--    ON CONFLICT (contract_status_event_id, processing_job)
--         WHERE processing_status = 'SUCCEEDED' DO NOTHING;
--    → 2회차 0 rows
--
-- ⑦ 처리기록 append-only (아래가 실패해야 정상)
--    UPDATE fgc.contract_status_event_processing SET processing_job='X';
--    → ERROR: contract_status_event_processing is append-only
--
-- ⑧ 작성자 없는 유산 정책 확인 (★ 운영 DB에서 반드시 먼저 돌릴 것)
--    SELECT policy_code, version_no, status, created_by, approved_by, approved_at
--      FROM fgc.policy_version
--     WHERE created_by IS NULL OR approved_by IS NULL;
--    → 로컬(V3 적용) : STATUS-MONTH-RULE-2026 v1 (RETIRED) 1행
--    → V3 미적용 DB  : STATUS-MONTH-RULE-2026 v1 (ACTIVE)  1행  ← 폐기 대상
-- ============================================================================
